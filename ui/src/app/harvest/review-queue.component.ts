import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatButtonToggleModule } from '@angular/material/button-toggle';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Observable, catchError, filter, finalize, map, of, switchMap } from 'rxjs';

import { percent } from '../core/ai/ai';
import { ConfigService } from '../core/config.service';
import {
  Classification,
  ClassificationSuggestion,
  ProposedStructure,
  acceptableAsSuggested,
  recordName,
} from '../core/harvest/harvest';
import { HarvestService } from '../core/harvest/harvest.service';
import { LocalizedDatePipe } from '../core/i18n/format.pipes';
import { I18nService } from '../core/i18n/i18n.service';
import { LdpService } from '../core/ldp/ldp.service';
import { normalizePath, pathOf, routeTo } from '../core/ldp/paths';
import { TopologyService } from '../core/topology/topology.service';
import { slugOf, structureKind, structureTurtle } from '../communities/communities';
import { ConfirmData, ConfirmDialogComponent } from '../shared/confirm-dialog.component';
import { Notifier } from '../shared/notifier.service';

/** Records per page. */
const PAGE = 25;

/**
 * The review queue of harvested records: where the AI agent suggests each record belongs (an
 * existing collection, or a new collection or community it proposes) and the topology of its item,
 * with its confidence and reasons. A person accepts a suggestion (the record is placed in the
 * collection, as an item of the topology), places the record elsewhere, rejects the suggestion
 * (the record stays where it was harvested) or has the record classified again.
 *
 * Proposed communities and collections are created only when the person accepts, here, with the
 * same structure the Communities page creates; a proposed topology is drafted on the Topologies
 * page. Uncertain suggestions are flagged and never accepted automatically.
 */
@Component({
  selector: 'ui-review-queue',
  imports: [
    DecimalPipe,
    FormsModule,
    LocalizedDatePipe,
    MatButtonModule,
    MatButtonToggleModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    RouterLink,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <section class="ui-review-queue" aria-labelledby="ui-review-heading">
      <header class="ui-page-header">
        <h2 id="ui-review-heading"><mat-icon inline aria-hidden="true">auto_awesome</mat-icon> {{ 'review.heading' | translate }}</h2>
        <div class="ui-row">
          <mat-form-field class="ui-review-source" subscriptSizing="dynamic">
            <mat-label>{{ 'review.source' | translate }}</mat-label>
            <mat-select [value]="source()" (valueChange)="filterBy($event, status())">
              <mat-option value="">{{ 'review.allSources' | translate }}</mat-option>
              @for (id of sources(); track id) {
                <mat-option [value]="id">{{ id }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
          <mat-button-toggle-group [value]="status()" (change)="filterBy(source(), $event.value)" [attr.aria-label]="'review.status' | translate">
            <mat-button-toggle value="open">{{ 'review.open' | translate }}</mat-button-toggle>
            <mat-button-toggle value="all">{{ 'review.all' | translate }}</mat-button-toggle>
          </mat-button-toggle-group>
          <button mat-button (click)="queue.reload()"><mat-icon aria-hidden="true">refresh</mat-icon> {{ 'harvest.refresh' | translate }}</button>
        </div>
      </header>
      <p class="ui-muted">{{ 'review.intro' | translate }}</p>
      @if (queue.isLoading()) {
        <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
      }
      @if (queue.error(); as error) {
        <p class="ui-error-text" role="alert">{{ message(error) }}</p>
      }
      @if (queue.value(); as q) {
        @if (!q.decide) {
          <p class="ui-muted ui-review-read-only">{{ 'review.readOnly' | translate }}</p>
        }
        @for (c of q.classifications; track c.record) {
          <mat-card appearance="outlined" class="ui-review-item" [class.ui-review-uncertain]="c.uncertain">
            <mat-card-header>
              <mat-card-title>
                <a [routerLink]="browse(c.placedAt || c.record)">{{ c.title || c.identifier }}</a>
              </mat-card-title>
              <mat-card-subtitle>
                <span class="ui-pill">{{ c.source }}</span>
                <span class="ui-pill ui-review-status-{{ c.status.toLowerCase() }}">{{ 'review.state.' + c.status | translate }}</span>
                @if (c.uncertain) {
                  <span class="ui-pill ui-review-uncertain-badge"><mat-icon inline aria-hidden="true">help</mat-icon> {{ 'review.uncertain' | translate }}</span>
                }
                @if (c.classifiedAt) {
                  {{ c.classifiedAt | l10nDate: 'short' }}
                }
              </mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              @if (c.status === 'FAILED') {
                <p class="ui-error-text">{{ 'review.failed' | translate: { reason: failure(c) } }}</p>
              }
              @if (c.suggestion; as s) {
                <p class="ui-review-placement"><strong>{{ 'review.placement' | translate }}</strong> {{ placement(s) }}</p>
                <p class="ui-review-topology"><strong>{{ 'review.topology' | translate }}</strong> {{ topology(s) }}</p>
                <p>
                  <span class="ui-pill">{{ 'ai.confidence' | translate: { percent: (confidence(s) | number) } }}</span>
                  @if (s.rationale) {
                    {{ s.rationale }}
                  }
                </p>
                @if ((s.alternatives ?? []).length > 0 && open(c) && q.decide) {
                  <p class="ui-muted">{{ 'review.alternatives' | translate }}</p>
                  <ul class="ui-review-alternatives">
                    @for (alternative of s.alternatives; track alternative.collection.path) {
                      <li>
                        {{ alternative.collection.trail }} ({{ 'ai.confidence' | translate: { percent: (percentOf(alternative.confidence) | number) } }})
                        <button mat-button [disabled]="busy().has(c.record)" (click)="acceptInto(c, alternative.collection.path, suggestedTopology(s))">
                          {{ 'review.useThis' | translate }}
                        </button>
                      </li>
                    }
                  </ul>
                }
              }
              @if (c.placedAt) {
                <p>{{ 'review.placedAt' | translate }} <a [routerLink]="browse(c.placedAt)">/{{ c.placedAt }}</a></p>
              }
              @if (c.decidedBy) {
                <p class="ui-muted">{{ 'review.decidedBy' | translate: { agent: c.decidedBy } }}</p>
              }
              @if (choosing() === c.record) {
                <form class="ui-form ui-review-choice" (ngSubmit)="acceptInto(c, chosenCollection, chosenTopology)">
                  <mat-form-field>
                    <mat-label>{{ 'review.collectionPath' | translate }}</mat-label>
                    <input matInput name="collection" required [(ngModel)]="chosenCollection" />
                    <mat-hint>{{ 'review.collectionHint' | translate }}</mat-hint>
                  </mat-form-field>
                  <mat-form-field>
                    <mat-label>{{ 'review.topology' | translate }}</mat-label>
                    <mat-select name="topology" [(ngModel)]="chosenTopology">
                      <mat-option value="none">{{ 'review.noTopology' | translate }}</mat-option>
                      @for (t of topologies.value() ?? []; track t.id) {
                        <mat-option [value]="t.id">{{ t.name }}</mat-option>
                      }
                    </mat-select>
                  </mat-form-field>
                  <div class="ui-row">
                    <button mat-flat-button color="primary" type="submit" [disabled]="busy().has(c.record) || !chosenCollection.trim()">{{ 'review.place' | translate }}</button>
                    <button mat-button type="button" (click)="choosing.set(undefined)">{{ 'common.cancel' | translate }}</button>
                  </div>
                </form>
              }
            </mat-card-content>
            @if (q.decide && open(c)) {
              <mat-card-actions>
                @if (acceptable(c)) {
                  <button mat-flat-button color="primary" class="ui-review-accept" [disabled]="busy().has(c.record)" (click)="accept(c)">
                    <mat-icon aria-hidden="true">check</mat-icon> {{ 'review.accept' | translate }}
                  </button>
                }
                @if (creates(c)) {
                  <button mat-flat-button color="primary" class="ui-review-create" [disabled]="busy().has(c.record)" (click)="createAndAccept(c)">
                    <mat-icon aria-hidden="true">create_new_folder</mat-icon> {{ 'review.createAndAccept' | translate }}
                  </button>
                }
                <button mat-stroked-button class="ui-review-choose" [disabled]="busy().has(c.record)" (click)="choose(c)">{{ 'review.choose' | translate }}</button>
                <button mat-button class="ui-review-reject" [disabled]="busy().has(c.record)" (click)="reject(c)">{{ 'review.reject' | translate }}</button>
                <button mat-button class="ui-review-retry" [disabled]="busy().has(c.record)" (click)="retry(c)">{{ 'review.retry' | translate }}</button>
              </mat-card-actions>
            }
          </mat-card>
        } @empty {
          <p class="ui-muted ui-review-empty">{{ (status() === 'open' ? 'review.emptyOpen' : 'review.empty') | translate }}</p>
        }
        @if (q.total > q.classifications.length) {
          <div class="ui-row ui-review-pages">
            <button mat-button [disabled]="offset() === 0" (click)="page(-1)">{{ 'review.previous' | translate }}</button>
            <span>{{ 'review.range' | translate: { from: q.offset + 1, to: q.offset + q.classifications.length, total: q.total } }}</span>
            <button mat-button [disabled]="q.offset + q.classifications.length >= q.total" (click)="page(1)">{{ 'review.next' | translate }}</button>
          </div>
        }
      }
    </section>
  `,
  styles: `
    .ui-review-item { margin-bottom: 12px; }
    .ui-review-item p { margin: 4px 0; }
    .ui-review-uncertain { border-left: 4px solid var(--mat-sys-tertiary, #b26a00); }
    .ui-review-choice { display: grid; gap: 8px; margin-top: 8px; max-width: 640px; }
    .ui-review-alternatives { margin: 0; }
    .ui-review-source { min-width: 180px; }
    mat-card-actions { flex-wrap: wrap; gap: 4px; }
  `,
})
export class ReviewQueueComponent {
  private readonly harvests = inject(HarvestService);

  private readonly ldp = inject(LdpService);

  private readonly topologyService = inject(TopologyService);

  private readonly config = inject(ConfigService);

  private readonly notifier = inject(Notifier);

  private readonly i18n = inject(I18nService);

  private readonly dialog = inject(MatDialog);

  /** The harvest sources, to filter by. */
  readonly sources = input<readonly string[]>([]);

  protected readonly source = signal('');

  protected readonly status = signal<'open' | 'all'>('open');

  protected readonly offset = signal(0);

  protected readonly queue = rxResource({
    params: () => ({ source: this.source(), status: this.status(), offset: this.offset() }),
    stream: ({ params }) =>
      this.harvests.classifications({ source: params.source || undefined, status: params.status, offset: params.offset, limit: PAGE }),
  });

  /** Topologies offered when the user places a record elsewhere (loaded when first needed). */
  private readonly topologiesWanted = signal(false);

  protected readonly topologies = rxResource({
    params: () => this.topologiesWanted() || undefined,
    stream: () => this.topologyService.list(),
  });

  /** Records with a request in flight. */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());

  /** The record whose placement is being chosen. */
  protected readonly choosing = signal<string | undefined>(undefined);

  protected chosenCollection = '';

  protected chosenTopology = 'none';

  protected readonly open = (c: Classification): boolean => ['PENDING', 'FAILED', 'PLACING'].includes(c.status);

  protected readonly acceptable = acceptableAsSuggested;

  protected readonly percentOf = percent;

  /** Proposed structures, waiting for a person to create them. */
  protected readonly creates = (c: Classification): boolean =>
    !!c.suggestion &&
    (c.suggestion.placement === 'NEW_COLLECTION' || c.suggestion.placement === 'NEW_COMMUNITY') &&
    c.suggestion.topology?.kind !== 'NEW';

  private readonly communities = computed(() => this.config.communitiesPath());

  /**
   * Shows another source or status, from the first page.
   *
   * @param source - the source, empty for every source
   * @param status - open or all
   */
  protected filterBy(source: string, status: 'open' | 'all'): void {
    this.source.set(source);
    this.status.set(status);
    this.offset.set(0);
  }

  /**
   * Moves a page forward or back.
   *
   * @param direction - `1` or `-1`
   */
  protected page(direction: number): void {
    this.offset.update((offset) => Math.max(0, offset + direction * PAGE));
  }

  /**
   * Describes where a suggestion places the record.
   *
   * @param s - the suggestion
   * @returns the text
   */
  protected placement(s: ClassificationSuggestion): string {
    switch (s.placement) {
      case 'EXISTING_COLLECTION':
        return s.collection?.trail ?? '';
      case 'NEW_COLLECTION':
        return this.i18n.t('review.newCollection', { title: s.newCollection?.title, community: s.community?.trail });
      case 'NEW_COMMUNITY':
        return this.i18n.t(s.community ? 'review.newSubCommunity' : 'review.newCommunity', {
          title: s.newCommunity?.title,
          collection: s.newCollection?.title,
          community: s.community?.trail,
        });
      default:
        return this.i18n.t('review.noPlacement');
    }
  }

  /**
   * Describes the topology a suggestion gives the record's item.
   *
   * @param s - the suggestion
   * @returns the text
   */
  protected topology(s: ClassificationSuggestion): string {
    if (s.topology?.kind === 'EXISTING') {
      return s.topology.name ?? s.topology.id ?? '';
    }
    if (s.topology?.kind === 'NEW') {
      return this.i18n.t('review.newTopology', { name: s.topology.name, description: s.topology.description ?? '' });
    }
    return this.i18n.t('review.noTopology');
  }

  /**
   * The suggested topology to place with: its id, or `none`.
   *
   * @param s - the suggestion
   * @returns the topology
   */
  protected suggestedTopology(s: ClassificationSuggestion): string {
    return s.topology?.kind === 'EXISTING' && s.topology.id ? s.topology.id : 'none';
  }

  /**
   * A suggestion's confidence as a percentage.
   *
   * @param s - the suggestion
   * @returns 0 to 100
   */
  protected confidence(s: ClassificationSuggestion): number {
    return percent(s.confidence);
  }

  /**
   * Explains why classification failed.
   *
   * @param c - the classification
   * @returns the reason, translated when known
   */
  protected failure(c: Classification): string {
    const key = `ai.reason.${c.failure === 'AGENT_UNAVAILABLE' ? 'UNREACHABLE' : c.failure}`;
    const text = this.i18n.t(key);
    return text === key ? (c.failure ?? '') : text;
  }

  /**
   * The browse route of a resource.
   *
   * @param path - its path
   * @returns the route
   */
  protected browse(path: string): string[] {
    return routeTo('browse', path);
  }

  /**
   * Accepts a suggestion as it is.
   *
   * @param c - the classification
   */
  protected accept(c: Classification): void {
    this.act(c, () => this.harvests.accept(c.source, recordName(c)), 'review.accepted', 'review.accept');
  }

  /**
   * Places a record in a collection with a topology.
   *
   * @param c - the classification
   * @param collection - the collection's path
   * @param topology - the topology's id, or `none`
   */
  protected acceptInto(c: Classification, collection: string, topology: string): void {
    const path = normalizePath(collection.trim());
    this.act(c, () => this.harvests.accept(c.source, recordName(c), { collection: path, topology }), 'review.accepted', 'review.place');
  }

  /**
   * Creates the proposed community and collection (once confirmed), then places the record there.
   *
   * @param c - the classification
   */
  protected createAndAccept(c: Classification): void {
    const s = c.suggestion;
    if (!s?.newCollection) {
      return;
    }
    const data: ConfirmData = {
      title: this.i18n.t('review.createAndAccept'),
      message: this.i18n.t('review.confirmCreate', { placement: this.placement(s) }),
      confirm: this.i18n.t('review.createAndAccept'),
    };
    const parent = s.community?.path ?? this.communities();
    const confirmed = this.dialog.open(ConfirmDialogComponent, { data }).afterClosed() as Observable<boolean>;
    this.act(
      c,
      () =>
        confirmed.pipe(
          filter((yes) => yes === true),
          switchMap(() =>
            s.placement === 'NEW_COMMUNITY' && s.newCommunity
              ? this.ensure(parent, 'community', s.newCommunity).pipe(
                  switchMap((community) => this.ensure(community, 'collection', s.newCollection!)),
                )
              : this.ensure(parent, 'collection', s.newCollection!),
          ),
          switchMap((collection) =>
            this.harvests.accept(c.source, recordName(c), { collection, topology: this.suggestedTopology(s) }),
          ),
        ),
      'review.accepted',
      'review.createAndAccept',
    );
  }

  /**
   * Opens the form placing a record elsewhere.
   *
   * @param c - the classification
   */
  protected choose(c: Classification): void {
    this.topologiesWanted.set(true);
    this.chosenCollection = c.suggestion?.collection?.path ?? c.suggestion?.alternatives?.[0]?.collection.path ?? '';
    this.chosenTopology = c.suggestion ? this.suggestedTopology(c.suggestion) : 'none';
    this.choosing.set(c.record);
  }

  /**
   * Rejects a suggestion, once confirmed: the record stays where it was harvested.
   *
   * @param c - the classification
   */
  protected reject(c: Classification): void {
    const data: ConfirmData = {
      title: this.i18n.t('review.reject'),
      message: this.i18n.t('review.confirmReject', { title: c.title || c.identifier }),
      confirm: this.i18n.t('review.reject'),
    };
    const confirmed = this.dialog.open(ConfirmDialogComponent, { data }).afterClosed() as Observable<boolean>;
    this.act(
      c,
      () => confirmed.pipe(filter((yes) => yes === true), switchMap(() => this.harvests.reject(c.source, recordName(c)))),
      'review.rejected',
      'review.reject',
    );
  }

  /**
   * Has a record classified again.
   *
   * @param c - the classification
   */
  protected retry(c: Classification): void {
    this.act(c, () => this.harvests.retry(c.source, recordName(c)), 'review.retried', 'review.retry');
  }

  /**
   * Formats a load failure.
   *
   * @param error - the failure
   * @returns the message
   */
  protected message(error: unknown): string {
    return this.notifier.message(error);
  }

  /**
   * Finds or creates a proposed community or collection: an existing one at the proposed name is
   * reused, so records proposing the same structure share it.
   */
  private ensure(parent: string, kind: 'community' | 'collection', proposed: ProposedStructure): Observable<string> {
    const slug = proposed.slug || slugOf(proposed.title);
    const path = `${normalizePath(parent)}/${slug}`;
    return this.ldp.load(path).pipe(
      map((view) => (structureKind(view.graph, view.subject) === kind ? path : undefined)),
      catchError(() => of(undefined)),
      switchMap((existing) =>
        existing
          ? of(existing)
          : this.ldp
              .createChild(parent, { kind: 'basic', slugHint: slug, turtle: structureTurtle(kind, proposed.title, proposed.description ?? undefined) })
              .pipe(map((url) => pathOf(this.ldp.base(), url) ?? path)),
      ),
    );
  }

  private act(c: Classification, request: () => Observable<Classification>, success: string, action: string): void {
    this.setBusy(c.record, true);
    request()
      .pipe(finalize(() => this.setBusy(c.record, false)))
      .subscribe({
        next: (updated) => {
          this.choosing.set(undefined);
          this.notifier.success(this.i18n.t(success, { title: updated.title || updated.identifier }));
          this.queue.reload();
        },
        error: (error: unknown) => {
          this.notifier.error(this.i18n.t(action), error);
          this.queue.reload();
        },
      });
  }

  private setBusy(record: string, busy: boolean): void {
    this.busy.update((records) => {
      const next = new Set(records);
      if (busy) {
        next.add(record);
      } else {
        next.delete(record);
      }
      return next;
    });
  }
}
