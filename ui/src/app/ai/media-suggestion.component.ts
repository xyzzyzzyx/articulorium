import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { TranslatePipe } from '@ngx-translate/core';
import { finalize } from 'rxjs';

import {
  MEDIA_NOTES,
  MediaMetadataSuggestion,
  MetadataStatement,
  aiErrorMessage,
  insertStatementsUpdate,
} from '../core/ai/ai';
import { AiService } from '../core/ai/ai.service';
import { PluralPipe } from '../core/i18n/format.pipes';
import { I18nService } from '../core/i18n/i18n.service';
import { LdpService } from '../core/ldp/ldp.service';
import { ResourceView } from '../core/ldp/models';
import { compact } from '../core/rdf/graph';
import { Notifier } from '../shared/notifier.service';

/** Media types the agent describes. */
const DESCRIBED = /^(image\/|video\/|application\/pdf)/;

/** Namespaces whose terms have translated labels (`ai.term.<local name>`). */
const LABELLED = ['http://purl.org/dc/terms/', 'http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#'];

/**
 * Suggests metadata for the binary shown (an image, a video or a PDF) with the AI agent: what its
 * file says (dimensions, durations, embedded titles and dates, a PDF's information) and what the
 * model suggests (title, description, subjects, creators, dates, places, genre). The user keeps
 * what is right and adds it to the binary's description; nothing is saved before that.
 *
 * Shown only when the agent offers it, to users who may change the description.
 */
@Component({
  selector: 'ui-media-suggestion',
  imports: [MatButtonModule, MatCheckboxModule, MatIconModule, MatProgressBarModule, MatTooltipModule, PluralPipe, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (offered()) {
      <section class="ui-ai-panel ui-media-suggestion" [attr.aria-busy]="busy()">
        <p class="ui-muted"><mat-icon inline aria-hidden="true">auto_awesome</mat-icon> {{ 'ai.media.intro' | translate }}</p>
        @if (busy()) {
          <mat-progress-bar mode="indeterminate" [attr.aria-label]="'ai.thinking' | translate" />
          <p class="ui-muted" role="status">{{ 'ai.thinking' | translate }}</p>
        }
        @if (error(); as message) {
          <p class="ui-error-text" role="alert">{{ message }}</p>
        }
        @if (suggestion(); as s) {
          @if (notes().length > 0) {
            <ul class="ui-ai-notes">
              @for (note of notes(); track note) {
                <li>{{ 'ai.media.note.' + note | translate }}</li>
              }
            </ul>
          }
          @if (s.statements.length === 0) {
            <p class="ui-muted ui-ai-nothing">{{ 'ai.media.nothing' | translate }}</p>
          } @else {
            <ul class="ui-ai-statements">
              @for (statement of s.statements; track $index) {
                <li>
                  <mat-checkbox [checked]="selected().has($index)" (change)="toggle($index)">
                    <span class="ui-ai-predicate" [matTooltip]="statement.predicate">{{ label(statement.predicate) }}</span>
                    <span class="ui-ai-value" [attr.lang]="statement.language || null">{{ statement.value }}</span>
                  </mat-checkbox>
                  <span class="ui-pill" [class.ui-ai-from-file]="statement.origin === 'FILE'">
                    {{ (statement.origin === 'FILE' ? 'ai.fromFile' : 'ai.suggested') | translate }}
                  </span>
                  @if (statement.language) {
                    <span class="ui-pill">{{ statement.language }}</span>
                  }
                </li>
              }
            </ul>
            <p class="ui-muted">{{ 'ai.review' | translate }}</p>
          }
          <div class="ui-row">
            @if (s.statements.length > 0) {
              <button mat-flat-button color="primary" class="ui-ai-apply" [disabled]="applying() || selected().size === 0" (click)="apply(s)">
                {{ 'ai.media.apply' | tplural: selected().size }}
              </button>
            }
            <button mat-button (click)="discard()">{{ 'ai.discard' | translate }}</button>
          </div>
        } @else if (!busy()) {
          <button mat-stroked-button class="ui-ai-suggest" (click)="suggest()">
            <mat-icon aria-hidden="true">auto_awesome</mat-icon> {{ 'ai.media.suggest' | translate }}
          </button>
        }
      </section>
    }
  `,
  styles: `
    .ui-ai-statements { list-style: none; padding: 0; margin: 0 0 8px; display: grid; gap: 4px; }
    .ui-ai-statements li { display: flex; flex-wrap: wrap; align-items: center; gap: 4px 8px; }
    .ui-ai-predicate { font-weight: 500; margin-right: 6px; }
    .ui-ai-value { overflow-wrap: anywhere; white-space: pre-wrap; }
    .ui-ai-notes { margin: 0 0 8px; padding-left: 20px; }
  `,
})
export class MediaSuggestionComponent {
  private readonly ai = inject(AiService);

  private readonly ldp = inject(LdpService);

  private readonly notifier = inject(Notifier);

  private readonly i18n = inject(I18nService);

  /** The binary shown. */
  readonly view = input.required<ResourceView>();

  /** Emitted once statements were added to the description. */
  readonly applied = output<void>();

  private readonly media = this.ai.offers('media');

  /** Whether the panel is shown: the agent describes media, and this binary is one it describes. */
  protected readonly offered = computed(() => this.media() && DESCRIBED.test(this.view().binary?.mimeType ?? ''));

  protected readonly busy = signal(false);

  protected readonly applying = signal(false);

  protected readonly error = signal<string | undefined>(undefined);

  protected readonly suggestion = signal<MediaMetadataSuggestion | undefined>(undefined);

  /** Indexes of the statements kept. */
  protected readonly selected = signal<ReadonlySet<number>>(new Set());

  protected readonly notes = computed(() => (this.suggestion()?.notes ?? []).filter((note) => MEDIA_NOTES.includes(note)));

  /** Asks the agent. */
  protected suggest(): void {
    this.busy.set(true);
    this.error.set(undefined);
    this.ai
      .describeMedia(this.view().path)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (suggestion) => {
          this.suggestion.set(suggestion);
          this.selected.set(new Set(suggestion.statements.map((_, index) => index)));
        },
        error: (error: unknown) =>
          this.error.set(aiErrorMessage(error, (key, params) => this.i18n.t(key, params), (e) => this.notifier.message(e))),
      });
  }

  /**
   * Keeps or drops a statement.
   *
   * @param index - the statement's index
   */
  protected toggle(index: number): void {
    this.selected.update((current) => {
      const next = new Set(current);
      if (next.has(index)) {
        next.delete(index);
      } else {
        next.add(index);
      }
      return next;
    });
  }

  /**
   * Adds the statements kept to the binary's description.
   *
   * @param suggestion - the suggestion
   */
  protected apply(suggestion: MediaMetadataSuggestion): void {
    const view = this.view();
    const kept: MetadataStatement[] = suggestion.statements.filter((_, index) => this.selected().has(index));
    const update = insertStatementsUpdate(view.subject.value, kept);
    if (!update || !view.binary) {
      return;
    }
    this.applying.set(true);
    this.ldp
      .patch(view.binary.descriptionUrl, update)
      .pipe(finalize(() => this.applying.set(false)))
      .subscribe({
        next: () => {
          this.notifier.success(this.i18n.plural('ai.media.applied', kept.length));
          this.discard();
          this.applied.emit();
        },
        error: (error: unknown) => this.notifier.error(this.i18n.plural('ai.media.apply', kept.length), error),
      });
  }

  /** Forgets the suggestion. */
  protected discard(): void {
    this.suggestion.set(undefined);
    this.selected.set(new Set());
    this.error.set(undefined);
  }

  /**
   * A readable name of a predicate.
   *
   * @param predicate - its IRI
   * @returns a translated label for Dublin Core and EBUCore terms, a prefixed name otherwise
   */
  protected label(predicate: string): string {
    const namespace = LABELLED.find((ns) => predicate.startsWith(ns));
    if (namespace) {
      const key = `ai.term.${predicate.substring(namespace.length)}`;
      const text = this.i18n.t(key);
      if (text !== key) {
        return text;
      }
    }
    return compact(predicate) ?? predicate;
  }
}
