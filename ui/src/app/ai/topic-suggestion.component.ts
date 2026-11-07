import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { finalize } from 'rxjs';

import { TopicModel, aiErrorMessage, percent, topicsUpdate } from '../core/ai/ai';
import { AiService } from '../core/ai/ai.service';
import { PluralPipe } from '../core/i18n/format.pipes';
import { I18nService } from '../core/i18n/i18n.service';
import { LdpService } from '../core/ldp/ldp.service';
import { ResourceView } from '../core/ldp/models';
import { routeTo } from '../core/ldp/paths';
import { allows } from '../core/webac/webac';
import { Notifier } from '../shared/notifier.service';

/**
 * Suggests the topics of a community or collection, and a summary of what it holds, with the AI
 * agent, from the titles, descriptions and subjects of its items (those the user may read). The
 * user keeps the topics that are right, and the summary, and adds them to the community or
 * collection (`dcterms:subject`, `dcterms:abstract`); nothing is saved before that.
 */
@Component({
  selector: 'ui-topic-suggestion',
  imports: [DecimalPipe, MatButtonModule, MatCheckboxModule, MatIconModule, MatProgressBarModule, PluralPipe, RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (offered()) {
      <section class="ui-ai-panel ui-topic-suggestion" [attr.aria-busy]="busy()">
        <h2>{{ 'ai.topics.heading' | translate }}</h2>
        <p class="ui-muted"><mat-icon inline aria-hidden="true">auto_awesome</mat-icon> {{ 'ai.topics.intro' | translate }}</p>
        @if (busy()) {
          <mat-progress-bar mode="indeterminate" [attr.aria-label]="'ai.thinking' | translate" />
          <p class="ui-muted" role="status">{{ 'ai.thinking' | translate }}</p>
        }
        @if (error(); as message) {
          <p class="ui-error-text" role="alert">{{ message }}</p>
        }
        @if (model(); as m) {
          @if (m.analysed === 0) {
            <p class="ui-muted ui-ai-nothing">{{ 'ai.topics.noItems' | translate }}</p>
          } @else {
            <p class="ui-muted">
              {{ 'ai.topics.analysed' | tplural: m.analysed }}
              @if (m.truncated) { {{ 'ai.topics.truncated' | translate }} }
            </p>
            @if (m.summary) {
              <mat-checkbox [checked]="keepSummary()" (change)="keepSummary.set(!keepSummary())">
                <strong>{{ 'ai.topics.summary' | translate }}</strong>
              </mat-checkbox>
              <p class="ui-ai-summary" [attr.lang]="m.language || null">{{ m.summary }}</p>
            }
            <ul class="ui-ai-topics">
              @for (topic of m.topics; track topic.label) {
                <li>
                  <mat-checkbox [checked]="selected().has(topic.label)" (change)="toggle(topic.label)">
                    <strong [attr.lang]="m.language || null">{{ topic.label }}</strong>
                  </mat-checkbox>
                  <span class="ui-pill">{{ share(topic.share) | number }} %</span>
                  @if (topic.description) {
                    <p class="ui-muted" [attr.lang]="m.language || null">{{ topic.description }}</p>
                  }
                  @if (topic.keywords.length > 0) {
                    <p class="ui-ai-keywords" [attr.lang]="m.language || null">{{ topic.keywords.join(', ') }}</p>
                  }
                  @if (topic.members.length > 0) {
                    <details>
                      <summary>{{ 'ai.topics.items' | tplural: topic.members.length }}</summary>
                      <ul>
                        @for (member of topic.members; track member.path) {
                          <li><a [routerLink]="route(member.path)">{{ member.title || member.path }}</a></li>
                        }
                      </ul>
                    </details>
                  }
                </li>
              }
            </ul>
            <p class="ui-muted">{{ 'ai.review' | translate }}</p>
          }
          <div class="ui-row">
            @if (m.analysed > 0) {
              <button mat-flat-button color="primary" class="ui-ai-apply" [disabled]="applying() || (selected().size === 0 && !keepSummary())" (click)="apply(m)">
                {{ 'ai.topics.apply' | translate }}
              </button>
            }
            <button mat-button (click)="discard()">{{ 'ai.discard' | translate }}</button>
          </div>
        } @else if (!busy()) {
          <button mat-stroked-button class="ui-ai-suggest" (click)="suggest()">
            <mat-icon aria-hidden="true">auto_awesome</mat-icon> {{ 'ai.topics.suggest' | translate }}
          </button>
        }
      </section>
    }
  `,
  styles: `
    .ui-ai-topics { list-style: none; padding: 0; margin: 8px 0; display: grid; gap: 8px; }
    .ui-ai-topics p { margin: 2px 0 0 32px; }
    .ui-ai-topics details { margin-left: 32px; }
    .ui-ai-summary { margin: 0 0 8px 32px; }
    .ui-ai-keywords { font-style: italic; }
  `,
})
export class TopicSuggestionComponent {
  private readonly ai = inject(AiService);

  private readonly ldp = inject(LdpService);

  private readonly notifier = inject(Notifier);

  private readonly i18n = inject(I18nService);

  /** The community or collection shown. */
  readonly view = input.required<ResourceView>();

  /** Emitted once topics were added. */
  readonly applied = output<void>();

  private readonly topics = this.ai.offers('topics');

  /** Whether the panel is shown: the agent models topics and the user may change the resource. */
  protected readonly offered = computed(() => this.topics() && allows(this.view().wacAllow, 'write'));

  protected readonly busy = signal(false);

  protected readonly applying = signal(false);

  protected readonly error = signal<string | undefined>(undefined);

  protected readonly model = signal<TopicModel | undefined>(undefined);

  /** Labels of the topics kept. */
  protected readonly selected = signal<ReadonlySet<string>>(new Set());

  protected readonly keepSummary = signal(true);

  protected readonly share = percent;

  /** Asks the agent. */
  protected suggest(): void {
    this.busy.set(true);
    this.error.set(undefined);
    this.ai
      .modelTopics(this.view().path)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (model) => {
          this.model.set(model);
          this.selected.set(new Set(model.topics.map((topic) => topic.label)));
          this.keepSummary.set(!!model.summary);
        },
        error: (error: unknown) =>
          this.error.set(aiErrorMessage(error, (key, params) => this.i18n.t(key, params), (e) => this.notifier.message(e))),
      });
  }

  /**
   * Keeps or drops a topic.
   *
   * @param label - the topic's label
   */
  protected toggle(label: string): void {
    this.selected.update((current) => {
      const next = new Set(current);
      if (next.has(label)) {
        next.delete(label);
      } else {
        next.add(label);
      }
      return next;
    });
  }

  /**
   * Adds the topics and summary kept to the community or collection.
   *
   * @param model - the topic model
   */
  protected apply(model: TopicModel): void {
    const view = this.view();
    const labels = model.topics.map((topic) => topic.label).filter((label) => this.selected().has(label));
    const update = topicsUpdate(view.subject.value, labels, model.language, this.keepSummary() ? model.summary : undefined);
    if (!update) {
      return;
    }
    this.applying.set(true);
    this.ldp
      .patch(view.url, update)
      .pipe(finalize(() => this.applying.set(false)))
      .subscribe({
        next: () => {
          this.notifier.success(
            labels.length > 0 ? this.i18n.plural('ai.topics.applied', labels.length) : this.i18n.t('ai.topics.appliedSummary'),
          );
          this.discard();
          this.applied.emit();
        },
        error: (error: unknown) => this.notifier.error(this.i18n.t('ai.topics.apply'), error),
      });
  }

  /** Forgets the suggestion. */
  protected discard(): void {
    this.model.set(undefined);
    this.selected.set(new Set());
    this.error.set(undefined);
  }

  /**
   * The browse route of an item.
   *
   * @param path - its path
   * @returns the route
   */
  protected route(path: string): string[] {
    return routeTo('browse', path);
  }
}
