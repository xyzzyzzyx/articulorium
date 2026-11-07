import { NgTemplateOutlet } from '@angular/common';
import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, InjectionToken, computed, effect, inject, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { TranslatePipe } from '@ngx-translate/core';
import { Observable, filter, finalize, of, switchMap } from 'rxjs';

import {
  ActionState,
  HarvestAction,
  HarvestReport,
  HarvestSource,
  HarvestSources,
  OUTCOME_ICONS,
  actionState,
  anyRunning,
  elapsed,
  formatDuration,
  severity,
} from '../core/harvest/harvest';
import { HarvestService } from '../core/harvest/harvest.service';
import { LocalizedDatePipe, LocalizedNumberPipe } from '../core/i18n/format.pipes';
import { I18nService } from '../core/i18n/i18n.service';
import { ConfirmData, ConfirmDialogComponent } from '../shared/confirm-dialog.component';
import { Notifier } from '../shared/notifier.service';

/** How often the page reads the sources again while a harvest runs, in milliseconds. */
export const HARVEST_POLL_INTERVAL = new InjectionToken<number>('HARVEST_POLL_INTERVAL', {
  providedIn: 'root',
  factory: () => 3000,
});

/** Display names of the source types the harvest service ships. */
const TYPE_NAMES: Readonly<Record<string, string>> = {
  'oai-pmh': 'OAI-PMH',
  dspace: 'DSpace',
  fedora: 'Fedora',
  vivo: 'VIVO',
};

/**
 * Operates the harvest service: lists the configured sources with what each supports, starts
 * incremental and full harvests, cancels running ones, follows their progress (reading the
 * sources again every few seconds while one runs) and shows the reports of the latest harvests,
 * with their failed records.
 *
 * The harvest service asks the platform's Web Access Control: viewing needs read and operating
 * write access to the harvested records. Only a harvest that ingested every record is shown as a
 * success; any other outcome says that the checkpoint did not move.
 */
@Component({
  selector: 'ui-harvest-page',
  imports: [
    LocalizedDatePipe,
    LocalizedNumberPipe,
    MatButtonModule,
    MatCardModule,
    MatExpansionModule,
    MatIconModule,
    MatProgressBarModule,
    NgTemplateOutlet,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  styles: `
    .ui-harvest-list {
      display: grid;
      /* a wide history table scrolls inside its card instead of widening the page */
      grid-template-columns: minmax(0, 1fr);
      gap: 16px;
      margin: 16px 0;
    }

    .ui-harvest-url {
      overflow-wrap: anywhere;
    }

    .ui-harvest-actions {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 12px 0 4px;
    }

    .ui-harvest-running {
      margin: 12px 0;
    }

    .ui-harvest-counts {
      display: grid;
      grid-template-columns: repeat(auto-fill, minmax(120px, 1fr));
      gap: 4px 16px;
      margin: 8px 0;

      div {
        display: flex;
        flex-direction: column;
      }

      dt {
        color: var(--ui-muted);
        font-size: 0.85rem;
      }

      dd {
        margin: 0;
        font-variant-numeric: tabular-nums;
      }
    }

    .ui-outcome {
      display: inline-flex;
      align-items: center;
      gap: 4px;
      padding: 2px 10px;
      border-radius: 12px;
      font-size: 0.9rem;
      font-weight: 500;

      mat-icon {
        font-size: 18px;
        width: 18px;
        height: 18px;
      }
    }

    .ui-outcome-success {
      background: #e8f5e9;
      color: #1b5e20;
    }

    .ui-outcome-warning {
      background: #fff3e0;
      color: #e65100;
    }

    .ui-outcome-error {
      background: #ffebee;
      color: #b71c1c;
    }

    .ui-outcome-neutral {
      background: #eceff1;
      color: #37474f;
    }

    .ui-harvest-failures {
      margin: 0;
      padding-inline-start: 20px;

      li {
        margin: 4px 0;
        overflow-wrap: anywhere;
      }
    }

    .ui-harvest-history {
      width: 100%;
      border-collapse: collapse;

      th,
      td {
        padding: 4px 8px;
        text-align: start;
        border-bottom: 1px solid rgba(0, 0, 0, 0.12);
      }

      td.ui-number {
        text-align: end;
        font-variant-numeric: tabular-nums;
      }
    }

    .ui-table-scroll {
      overflow-x: auto;
    }

    h3 {
      margin: 16px 0 4px;
      font-size: 1rem;
    }
  `,
  template: `
    <header class="ui-page-header">
      <div>
        <h1>{{ 'harvest.heading' | translate }}</h1>
        <p class="ui-muted">{{ 'harvest.intro' | translate }}</p>
      </div>
      <button mat-stroked-button type="button" class="ui-harvest-refresh" [disabled]="sources.isLoading()" (click)="sources.reload()">
        <mat-icon aria-hidden="true">refresh</mat-icon> {{ 'harvest.refresh' | translate }}
      </button>
    </header>
    @if (sources.isLoading() && !data()) {
      <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
    }
    @if (loadError(); as message) {
      <div class="ui-banner ui-harvest-error" role="alert">
        <mat-icon aria-hidden="true">error_outline</mat-icon>
        <span class="ui-grow">{{ message }}</span>
        <button mat-button type="button" (click)="sources.reload()">{{ 'common.retry' | translate }}</button>
      </div>
    }
    @if (data(); as d) {
      @if (!d.operate) {
        <p class="ui-banner ui-harvest-read-only"><mat-icon aria-hidden="true">visibility</mat-icon> {{ 'harvest.readOnly' | translate }}</p>
      }
      <div class="ui-harvest-list">
        @for (source of d.sources; track source.id) {
          <mat-card appearance="outlined" class="ui-harvest-source" [attr.data-source]="source.id">
            <mat-card-header>
              <mat-icon mat-card-avatar aria-hidden="true">cloud_download</mat-icon>
              <mat-card-title>{{ source.id }}</mat-card-title>
              <mat-card-subtitle>
                {{ typeName(source.type) }} · <span class="ui-harvest-url">{{ source.url }}</span>
              </mat-card-subtitle>
            </mat-card-header>
            <mat-card-content>
              <div class="ui-chip-row ui-harvest-capabilities">
                @if (!source.enabled) {
                  <span class="ui-pill ui-pill-warn"><mat-icon aria-hidden="true">block</mat-icon> {{ 'harvest.capability.disabled' | translate }}</span>
                }
                <span class="ui-pill">{{ (source.capabilities.incremental ? 'harvest.capability.incremental' : 'harvest.capability.fullOnly') | translate }}</span>
                <span class="ui-pill">{{ (source.capabilities.deletions ? 'harvest.capability.deletions' : 'harvest.capability.noDeletions') | translate }}</span>
                <span class="ui-pill">
                  {{ source.schedule ? ('harvest.capability.scheduled' | translate: { schedule: source.schedule }) : ('harvest.capability.onDemand' | translate) }}
                </span>
              </div>

              @if (source.running; as run) {
                <section class="ui-harvest-running" aria-live="polite">
                  <mat-progress-bar mode="indeterminate" [attr.aria-label]="'harvest.running.' + run.mode | translate" />
                  <p>
                    <strong>{{ 'harvest.running.' + run.mode | translate }}</strong> ·
                    {{ 'harvest.running.since' | translate: { time: (run.startedAt | l10nDate) } }}
                  </p>
                  <dl class="ui-harvest-counts">
                    <div><dt>{{ 'harvest.field.listed' | translate }}</dt><dd>{{ run.harvested | l10nNumber }}</dd></div>
                    <div><dt>{{ 'harvest.field.failed' | translate }}</dt><dd>{{ run.failed | l10nNumber }}</dd></div>
                  </dl>
                  @if (run.cancelled) {
                    <p class="ui-muted">{{ 'harvest.running.stopping' | translate }}</p>
                  }
                </section>
              }

              @let incremental = state(source, 'incremental', d.operate);
              @let full = state(source, 'full', d.operate);
              <div class="ui-harvest-actions">
                <button
                  mat-flat-button
                  color="primary"
                  type="button"
                  class="ui-harvest-incremental"
                  [disabled]="!incremental.allowed || busy().has(source.id)"
                  (click)="start(source, 'incremental')"
                >
                  <mat-icon aria-hidden="true">update</mat-icon> {{ 'harvest.action.incremental' | translate }}
                </button>
                <button
                  mat-stroked-button
                  type="button"
                  class="ui-harvest-full"
                  [disabled]="!full.allowed || busy().has(source.id)"
                  (click)="start(source, 'full')"
                >
                  <mat-icon aria-hidden="true">sync</mat-icon> {{ 'harvest.action.full' | translate }}
                </button>
                @if (source.running) {
                  @let stop = state(source, 'cancel', d.operate);
                  <button
                    mat-stroked-button
                    color="warn"
                    type="button"
                    class="ui-harvest-cancel"
                    [disabled]="!stop.allowed || busy().has(source.id)"
                    (click)="cancel(source)"
                  >
                    <mat-icon aria-hidden="true">stop</mat-icon> {{ 'harvest.action.cancel' | translate }}
                  </button>
                }
              </div>
              @if (d.operate && whyNot(incremental, full); as reason) {
                <p class="ui-hint ui-harvest-why">{{ 'harvest.why.' + reason | translate }}</p>
              }

              @if (source.reports[0]; as last) {
                <h3>{{ 'harvest.last' | translate }}</h3>
                <ng-container *ngTemplateOutlet="report; context: { $implicit: last }" />
                @if (source.reports.length > 1) {
                  <mat-expansion-panel class="ui-harvest-earlier">
                    <mat-expansion-panel-header>
                      <mat-panel-title>{{ 'harvest.earlier' | translate: { count: source.reports.length - 1 } }}</mat-panel-title>
                    </mat-expansion-panel-header>
                    <div class="ui-table-scroll">
                      <table class="ui-harvest-history">
                        <thead>
                          <tr>
                            <th scope="col">{{ 'harvest.field.outcome' | translate }}</th>
                            <th scope="col">{{ 'harvest.field.mode' | translate }}</th>
                            <th scope="col">{{ 'harvest.field.started' | translate }}</th>
                            <th scope="col">{{ 'harvest.field.duration' | translate }}</th>
                            <th scope="col">{{ 'harvest.field.listed' | translate }}</th>
                            <th scope="col">{{ 'harvest.field.failed' | translate }}</th>
                          </tr>
                        </thead>
                        <tbody>
                          @for (earlier of source.reports.slice(1); track earlier.startedAt) {
                            <tr>
                              <td>
                                <span class="ui-outcome ui-outcome-{{ severityOf(earlier) }}">
                                  <mat-icon aria-hidden="true">{{ icon(earlier) }}</mat-icon> {{ 'harvest.outcome.' + earlier.outcome | translate }}
                                </span>
                              </td>
                              <td>{{ 'harvest.mode.' + earlier.mode | translate }}</td>
                              <td>{{ earlier.startedAt | l10nDate: 'short' }}</td>
                              <td>{{ duration(earlier) }}</td>
                              <td class="ui-number">{{ earlier.harvested | l10nNumber }}</td>
                              <td class="ui-number">{{ earlier.failed | l10nNumber }}</td>
                            </tr>
                          }
                        </tbody>
                      </table>
                    </div>
                  </mat-expansion-panel>
                }
              } @else if (!source.running) {
                <p class="ui-muted ui-harvest-never">{{ 'harvest.never' | translate }}</p>
              }
            </mat-card-content>
          </mat-card>
        } @empty {
          <p class="ui-muted ui-harvest-none">{{ 'harvest.none' | translate }}</p>
        }
      </div>
    }

    <ng-template #report let-r>
      <div class="ui-harvest-report">
        <p>
          <span class="ui-outcome ui-outcome-{{ severityOf(r) }}" role="status">
            <mat-icon aria-hidden="true">{{ icon(r) }}</mat-icon> {{ 'harvest.outcome.' + r.outcome | translate }}
          </span>
        </p>
        @if (r.message) {
          <p class="ui-harvest-message" [class.ui-error-text]="r.outcome === 'FAILED'">
            <strong>{{ 'harvest.field.reason' | translate }}:</strong> {{ r.message }}
          </p>
        }
        <dl class="ui-harvest-counts">
          <div><dt>{{ 'harvest.field.mode' | translate }}</dt><dd>{{ 'harvest.mode.' + r.mode | translate }}</dd></div>
          <div><dt>{{ 'harvest.field.started' | translate }}</dt><dd>{{ r.startedAt | l10nDate: 'short' }}</dd></div>
          <div><dt>{{ 'harvest.field.duration' | translate }}</dt><dd>{{ duration(r) }}</dd></div>
          @if (r.from) {
            <div><dt>{{ 'harvest.field.window' | translate }}</dt><dd>{{ r.from | l10nDate: 'short' }}</dd></div>
          }
          <div><dt>{{ 'harvest.field.listed' | translate }}</dt><dd class="ui-count-harvested">{{ r.harvested | l10nNumber }}</dd></div>
          <div><dt>{{ 'harvest.field.created' | translate }}</dt><dd class="ui-count-created">{{ r.created | l10nNumber }}</dd></div>
          <div><dt>{{ 'harvest.field.updated' | translate }}</dt><dd class="ui-count-updated">{{ r.updated | l10nNumber }}</dd></div>
          <div><dt>{{ 'harvest.field.deleted' | translate }}</dt><dd class="ui-count-deleted">{{ r.deleted | l10nNumber }}</dd></div>
          <div><dt>{{ 'harvest.field.skipped' | translate }}</dt><dd class="ui-count-skipped">{{ r.skipped | l10nNumber }}</dd></div>
          <div><dt>{{ 'harvest.field.failed' | translate }}</dt><dd class="ui-count-failed">{{ r.failed | l10nNumber }}</dd></div>
          <div><dt>{{ 'harvest.field.pages' | translate }}</dt><dd>{{ r.pages | l10nNumber }}</dd></div>
        </dl>
        <p class="ui-hint ui-harvest-checkpoint">
          @if (r.checkpoint) {
            {{ 'harvest.checkpoint.moved' | translate: { time: (r.checkpoint | l10nDate: 'short') } }}
          } @else {
            {{ 'harvest.checkpoint.kept' | translate }}
          }
        </p>
        @if (r.failures.length > 0) {
          <mat-expansion-panel class="ui-harvest-failures-panel">
            <mat-expansion-panel-header>
              <mat-panel-title>{{ 'harvest.failures.heading' | translate: { count: r.failed } }}</mat-panel-title>
            </mat-expansion-panel-header>
            @if (r.failures.length < r.failed) {
              <p class="ui-hint">{{ 'harvest.failures.partial' | translate: { shown: r.failures.length, count: r.failed } }}</p>
            }
            <ul class="ui-harvest-failures">
              @for (failure of r.failures; track $index) {
                <li><code>{{ failure.identifier }}</code>: {{ failure.reason }}</li>
              }
            </ul>
          </mat-expansion-panel>
        }
      </div>
    </ng-template>
  `,
})
export class HarvestPageComponent {
  private readonly harvests = inject(HarvestService);

  private readonly notifier = inject(Notifier);

  private readonly i18n = inject(I18nService);

  private readonly dialog = inject(MatDialog);

  private readonly pollInterval = inject(HARVEST_POLL_INTERVAL);

  protected readonly sources = rxResource({ stream: () => this.harvests.list() });

  /** The latest sources read, kept while a refresh fails or after an action changed one. */
  protected readonly data = signal<HarvestSources | undefined>(undefined);

  /** Sources with a request in flight. */
  protected readonly busy = signal<ReadonlySet<string>>(new Set());

  protected readonly loadError = computed(() => {
    const error = this.sources.error();
    return error === undefined ? undefined : this.failure(error);
  });

  constructor() {
    effect(() => {
      if (this.sources.hasValue()) {
        this.data.set(this.sources.value());
      }
    });
    // follow running harvests; a failed refresh stops following until the user retries
    effect((onCleanup) => {
      if (this.sources.isLoading() || this.sources.error() !== undefined || !anyRunning(this.data()?.sources)) {
        return;
      }
      const timer = setTimeout(() => this.sources.reload(), this.pollInterval);
      onCleanup(() => clearTimeout(timer));
    });
  }

  /**
   * Starts a harvest; a full harvest is confirmed first.
   *
   * @param source - the source
   * @param mode - the mode
   */
  protected start(source: HarvestSource, mode: 'incremental' | 'full'): void {
    const confirmed: Observable<boolean> =
      mode === 'full' ? this.confirm('harvest.confirmFull', source, this.i18n.t('harvest.action.full')) : of(true);
    this.act(source, confirmed, () => this.harvests.start(source.id, mode), 'harvest.started', `harvest.action.${mode}`);
  }

  /**
   * Asks the running harvest of a source to stop, once confirmed.
   *
   * @param source - the source
   */
  protected cancel(source: HarvestSource): void {
    const confirmed = this.confirm('harvest.confirmCancel', source, this.i18n.t('harvest.action.cancel'));
    this.act(source, confirmed, () => this.harvests.cancel(source.id), 'harvest.stopping', 'harvest.action.cancel');
  }

  /**
   * Whether an action is offered.
   *
   * @param source - the source
   * @param action - the action
   * @param operate - whether the user may operate
   * @returns the state
   */
  protected state(source: HarvestSource, action: HarvestAction, operate: boolean): ActionState {
    return actionState(source, action, operate);
  }

  /**
   * Why the start buttons are disabled, when they are.
   *
   * @param incremental - the incremental harvest's state
   * @param full - the full harvest's state
   * @returns the reason, or `undefined`
   */
  protected whyNot(incremental: ActionState, full: ActionState): string | undefined {
    return full.reason ?? incremental.reason;
  }

  /**
   * The display name of a source type.
   *
   * @param type - the type
   * @returns for example `OAI-PMH`
   */
  protected typeName(type: string): string {
    return TYPE_NAMES[type] ?? type;
  }

  /**
   * The severity of a report's outcome.
   *
   * @param report - the report
   * @returns the severity
   */
  protected severityOf(report: HarvestReport): string {
    return severity(report.outcome);
  }

  /**
   * The icon of a report's outcome.
   *
   * @param report - the report
   * @returns the Material icon name
   */
  protected icon(report: HarvestReport): string {
    return OUTCOME_ICONS[report.outcome] ?? 'help';
  }

  /**
   * How long a harvest took.
   *
   * @param report - the report
   * @returns the formatted duration
   */
  protected duration(report: HarvestReport): string {
    const milliseconds = elapsed(report.startedAt, report.finishedAt);
    return milliseconds === undefined ? this.i18n.t('common.noValue') : formatDuration(milliseconds, this.i18n.locale());
  }

  private confirm(key: string, source: HarvestSource, action: string): Observable<boolean> {
    const data: ConfirmData = {
      title: this.i18n.t(`${key}.title`),
      message: this.i18n.t(`${key}.message`, { source: source.id }),
      confirm: action,
    };
    return this.dialog.open(ConfirmDialogComponent, { data }).afterClosed();
  }

  private act(
    source: HarvestSource,
    confirmed: Observable<boolean>,
    request: () => Observable<HarvestSource>,
    success: string,
    action: string,
  ): void {
    confirmed
      .pipe(
        filter((yes) => yes === true),
        switchMap(() => {
          this.setBusy(source.id, true);
          return request().pipe(finalize(() => this.setBusy(source.id, false)));
        }),
      )
      .subscribe({
        next: (updated) => {
          this.replace(updated);
          this.notifier.success(this.i18n.t(success, { source: source.id }));
        },
        error: (error: unknown) => {
          this.notifier.error(this.i18n.t(action), error);
          // the source changed meanwhile (409) or access did: show what is true now
          this.sources.reload();
        },
      });
  }

  private replace(updated: HarvestSource): void {
    this.data.update((current) =>
      current
        ? { ...current, sources: current.sources.map((source) => (source.id === updated.id ? updated : source)) }
        : current,
    );
  }

  private setBusy(id: string, busy: boolean): void {
    this.busy.update((ids) => {
      const next = new Set(ids);
      if (busy) {
        next.add(id);
      } else {
        next.delete(id);
      }
      return next;
    });
  }

  private failure(error: unknown): string {
    const http = error instanceof HttpErrorResponse ? error : error instanceof Error ? error.cause : undefined;
    if (http instanceof HttpErrorResponse) {
      if (http.status === 401) {
        return this.i18n.t('harvest.error.signIn');
      }
      if (http.status === 403) {
        return this.i18n.t('harvest.error.forbidden');
      }
      // without a problem document from the harvest service, the service itself is not there
      const fromService = typeof (http.error as { detail?: unknown } | null)?.detail === 'string';
      if (!fromService && [0, 404, 502, 503, 504].includes(http.status)) {
        return this.i18n.t('harvest.error.unavailable');
      }
    }
    return this.notifier.message(error);
  }
}
