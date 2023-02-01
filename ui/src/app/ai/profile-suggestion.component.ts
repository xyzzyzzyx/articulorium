import { ChangeDetectionStrategy, Component, computed, inject, input, output, signal } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MatCheckboxModule } from '@angular/material/checkbox';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { TranslatePipe } from '@ngx-translate/core';
import { finalize } from 'rxjs';

import { ProfileSuggestion, aiErrorMessage, mergeProfileSuggestion, suggestionHasMaterial } from '../core/ai/ai';
import { AiService } from '../core/ai/ai.service';
import { I18nService } from '../core/i18n/i18n.service';
import { LdpService } from '../core/ldp/ldp.service';
import { ResourceView } from '../core/ldp/models';
import { ProfileDocument, profileTurtle, validateProfile } from '../core/profile/profile';
import { allows } from '../core/webac/webac';
import { Notifier } from '../shared/notifier.service';

/**
 * Suggests what an academic profile lacks with the AI agent: an overview, research areas and
 * keywords, drawn from the positions, degrees and publications the profile records. The user keeps
 * what is right; it is merged into the profile and saved as the profile editor saves it (one
 * replacement guarded by the entity tag the profile was read with), so nothing is saved before
 * that and a profile changed meanwhile is never overwritten.
 *
 * Shown only when the agent offers it, to users who may change the profile.
 */
@Component({
  selector: 'ui-profile-suggestion',
  imports: [MatButtonModule, MatCheckboxModule, MatIconModule, MatProgressBarModule, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (offered()) {
      <section class="ui-ai-panel ui-profile-suggestion" [attr.aria-busy]="busy()">
        <h2>{{ 'ai.profiles.heading' | translate }}</h2>
        <p class="ui-muted"><mat-icon inline aria-hidden="true">auto_awesome</mat-icon> {{ 'ai.profiles.intro' | translate }}</p>
        @if (busy()) {
          <mat-progress-bar mode="indeterminate" [attr.aria-label]="'ai.thinking' | translate" />
          <p class="ui-muted" role="status">{{ 'ai.thinking' | translate }}</p>
        }
        @if (error(); as message) {
          <p class="ui-error-text" role="alert">{{ message }}</p>
        }
        @if (suggestion(); as s) {
          @if (!hasMaterial(s)) {
            <p class="ui-muted ui-ai-nothing">{{ 'ai.profiles.noMaterial' | translate }}</p>
          } @else if (empty(s)) {
            <p class="ui-muted ui-ai-nothing">{{ 'ai.profiles.nothing' | translate }}</p>
          } @else {
            @if (s.overview) {
              <mat-checkbox class="ui-ai-overview" [checked]="keepOverview()" (change)="keepOverview.set(!keepOverview())">
                <strong>{{ 'ai.profiles.overview' | translate }}</strong>
              </mat-checkbox>
              @if (s.hasOverview) {
                <p class="ui-muted ui-ai-replaces">{{ 'ai.profiles.replacesOverview' | translate }}</p>
              }
              <p class="ui-ai-summary" [attr.lang]="s.language || null">{{ s.overview }}</p>
            }
            @if (s.researchAreas.length > 0) {
              <h3>{{ 'ai.profiles.researchAreas' | translate }}</h3>
              <ul class="ui-ai-choices ui-ai-areas">
                @for (area of s.researchAreas; track area) {
                  <li>
                    <mat-checkbox [checked]="areas().has(area)" (change)="toggle(areas, area)">
                      <span [attr.lang]="s.language || null">{{ area }}</span>
                    </mat-checkbox>
                  </li>
                }
              </ul>
            }
            @if (s.keywords.length > 0) {
              <h3>{{ 'ai.profiles.keywords' | translate }}</h3>
              <ul class="ui-ai-choices ui-ai-keywords">
                @for (keyword of s.keywords; track keyword) {
                  <li>
                    <mat-checkbox [checked]="keywords().has(keyword)" (change)="toggle(keywords, keyword)">
                      <span [attr.lang]="s.language || null">{{ keyword }}</span>
                    </mat-checkbox>
                  </li>
                }
              </ul>
            }
            <p class="ui-muted">{{ 'ai.review' | translate }}</p>
          }
          <div class="ui-row">
            @if (hasMaterial(s) && !empty(s)) {
              <button mat-flat-button color="primary" class="ui-ai-apply" [disabled]="applying() || !kept()" (click)="apply(s)">
                {{ 'ai.profiles.apply' | translate }}
              </button>
            }
            <button mat-button class="ui-ai-discard" (click)="discard()">{{ 'ai.discard' | translate }}</button>
          </div>
        } @else if (!busy()) {
          <button mat-stroked-button class="ui-ai-suggest" (click)="suggest()">
            <mat-icon aria-hidden="true">auto_awesome</mat-icon> {{ 'ai.profiles.suggest' | translate }}
          </button>
        }
      </section>
    }
  `,
  styles: `
    .ui-ai-choices { list-style: none; padding: 0; margin: 0 0 8px; display: flex; flex-wrap: wrap; gap: 0 16px; }
    .ui-ai-summary { margin: 0 0 8px 32px; white-space: pre-wrap; }
    .ui-ai-replaces { margin: 0 0 4px 32px; }
    h3 { margin: 12px 0 4px; font-size: 1rem; }
  `,
})
export class ProfileSuggestionComponent {
  private readonly ai = inject(AiService);

  private readonly ldp = inject(LdpService);

  private readonly notifier = inject(Notifier);

  private readonly i18n = inject(I18nService);

  /** The profile's resource, as read (its entity tag guards the save). */
  readonly view = input.required<ResourceView>();

  /** The profile as read, with the statements the editor keeps as they are. */
  readonly document = input.required<ProfileDocument>();

  /** Emitted once the profile was saved. */
  readonly applied = output<void>();

  private readonly profiles = this.ai.offers('profiles');

  /** Whether the panel is shown: the agent suggests for profiles and the user may change this one. */
  protected readonly offered = computed(() => this.profiles() && allows(this.view().wacAllow, 'write'));

  protected readonly busy = signal(false);

  protected readonly applying = signal(false);

  protected readonly error = signal<string | undefined>(undefined);

  protected readonly suggestion = signal<ProfileSuggestion | undefined>(undefined);

  protected readonly keepOverview = signal(false);

  protected readonly areas = signal<ReadonlySet<string>>(new Set());

  protected readonly keywords = signal<ReadonlySet<string>>(new Set());

  /** Whether anything is kept. */
  protected readonly kept = computed(
    () => (this.keepOverview() && !!this.suggestion()?.overview) || this.areas().size > 0 || this.keywords().size > 0,
  );

  protected readonly hasMaterial = suggestionHasMaterial;

  /**
   * Tells whether a suggestion suggests nothing.
   *
   * @param s - the suggestion
   * @returns `true` without an overview, research areas or keywords
   */
  protected empty(s: ProfileSuggestion): boolean {
    return !s.overview && s.researchAreas.length === 0 && s.keywords.length === 0;
  }

  /** Asks the agent. */
  protected suggest(): void {
    this.busy.set(true);
    this.error.set(undefined);
    this.ai
      .suggestProfile(this.view().path)
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (suggestion) => {
          this.suggestion.set(suggestion);
          // an overview the profile lacks is offered kept; replacing one is the user's choice
          this.keepOverview.set(!!suggestion.overview && !suggestion.hasOverview);
          this.areas.set(new Set(suggestion.researchAreas));
          this.keywords.set(new Set(suggestion.keywords));
        },
        error: (error: unknown) =>
          this.error.set(aiErrorMessage(error, (key, params) => this.i18n.t(key, params), (e) => this.notifier.message(e))),
      });
  }

  /**
   * Keeps or drops a research area or keyword.
   *
   * @param kept - the kept set
   * @param value - the value
   */
  protected toggle(kept: typeof this.areas, value: string): void {
    kept.update((current) => {
      const next = new Set(current);
      if (next.has(value)) {
        next.delete(value);
      } else {
        next.add(value);
      }
      return next;
    });
  }

  /**
   * Merges what is kept into the profile and saves it.
   *
   * @param s - the suggestion
   */
  protected apply(s: ProfileSuggestion): void {
    const view = this.view();
    const document = this.document();
    const merged = mergeProfileSuggestion(document.profile, {
      overview: this.keepOverview() ? s.overview : undefined,
      researchAreas: s.researchAreas.filter((area) => this.areas().has(area)),
      keywords: s.keywords.filter((keyword) => this.keywords().has(keyword)),
    });
    const issues = validateProfile(merged);
    if (issues.length > 0) {
      this.error.set(this.i18n.t(issues[0].key, { ...issues[0].params }));
      return;
    }
    this.applying.set(true);
    this.ldp
      .replace(view.path, profileTurtle(merged, view.subject.value, document.preserved), view.etag)
      .pipe(finalize(() => this.applying.set(false)))
      .subscribe({
        next: () => {
          this.notifier.success(this.i18n.t('ai.profiles.applied'));
          this.discard();
          this.applied.emit();
        },
        error: (error: unknown) => this.notifier.error(this.i18n.t('ai.profiles.apply'), error),
      });
  }

  /** Forgets the suggestion. */
  protected discard(): void {
    this.suggestion.set(undefined);
    this.keepOverview.set(false);
    this.areas.set(new Set());
    this.keywords.set(new Set());
    this.error.set(undefined);
  }
}
