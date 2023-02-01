import { DecimalPipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { finalize } from 'rxjs';

import { TopologySuggestion, aiErrorMessage, completeTopology, percent } from '../core/ai/ai';
import { AiService, TopologyDraftStore } from '../core/ai/ai.service';
import { I18nService } from '../core/i18n/i18n.service';
import { normalizePath } from '../core/ldp/paths';
import { Notifier } from '../shared/notifier.service';

/** What the dialog starts with. */
export interface TopologySuggestionData {
  /** A description of the material, prefilled (for example from a harvested record's classification). */
  readonly description?: string;
  /** The container the user works in (a collection). */
  readonly context?: string;
}

/**
 * Asks the AI agent which topology fits some material: the user describes it (and may name a
 * container holding an example), the agent recommends an existing topology or drafts a new one.
 * A draft opens in the topology editor, where the user reviews, changes and saves it; nothing is
 * saved before that.
 */
@Component({
  selector: 'ui-topology-suggestion-dialog',
  imports: [
    DecimalPipe,
    FormsModule,
    MatButtonModule,
    MatDialogModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 mat-dialog-title>{{ 'ai.topology.heading' | translate }}</h2>
    <mat-dialog-content>
      <p class="ui-muted">{{ 'ai.topology.intro' | translate }}</p>
      <form class="ui-form ui-topology-suggestion-form" (ngSubmit)="suggest()">
        <mat-form-field>
          <mat-label>{{ 'ai.topology.description' | translate }}</mat-label>
          <textarea matInput name="description" rows="4" [(ngModel)]="description" [placeholder]="'ai.topology.placeholder' | translate"></textarea>
        </mat-form-field>
        <mat-form-field>
          <mat-label>{{ 'ai.topology.sample' | translate }}</mat-label>
          <input matInput name="sample" [(ngModel)]="sample" [placeholder]="'ai.topology.samplePlaceholder' | translate" />
          <mat-hint>{{ 'ai.topology.sampleHint' | translate }}</mat-hint>
        </mat-form-field>
        <button mat-flat-button color="primary" type="submit" class="ui-ai-suggest" [disabled]="busy() || (!description.trim() && !sample.trim())">
          <mat-icon aria-hidden="true">auto_awesome</mat-icon> {{ 'ai.topology.suggest' | translate }}
        </button>
      </form>
      @if (busy()) {
        <mat-progress-bar mode="indeterminate" [attr.aria-label]="'ai.thinking' | translate" />
        <p class="ui-muted" role="status">{{ 'ai.thinking' | translate }}</p>
      }
      @if (error(); as message) {
        <p class="ui-error-text" role="alert">{{ message }}</p>
      }
      @if (suggestion(); as s) {
        <section class="ui-ai-result" aria-live="polite">
          @if (s.recommendation === 'EXISTING' && s.existing; as existing) {
            <h3>{{ 'ai.topology.existing' | translate: { name: existing.name } }}</h3>
          } @else if (s.draft) {
            <h3>{{ 'ai.topology.new' | translate: { name: s.draft.name || s.draft.id } }}</h3>
            @if (s.draft.description) {
              <p>{{ s.draft.description }}</p>
            }
          }
          <p>
            <span class="ui-pill">{{ 'ai.confidence' | translate: { percent: (confidence(s) | number) } }}</span>
            @if (s.rationale) {
              {{ s.rationale }}
            }
          </p>
          @if (s.errors.length > 0) {
            <p class="ui-muted">{{ 'ai.topology.errors' | translate }}</p>
            <ul class="ui-error-text">
              @for (problem of s.errors; track problem) {
                <li>{{ problem }}</li>
              }
            </ul>
          }
          <p class="ui-muted">{{ 'ai.review' | translate }}</p>
        </section>
      }
    </mat-dialog-content>
    <mat-dialog-actions align="end">
      <button mat-button mat-dialog-close>{{ 'common.close' | translate }}</button>
      @if (suggestion(); as s) {
        @if (s.recommendation === 'EXISTING' && s.existing; as existing) {
          <button mat-flat-button color="primary" class="ui-ai-open" (click)="open(existing.id)">{{ 'ai.topology.view' | translate }}</button>
        } @else if (s.draft) {
          <button mat-flat-button color="primary" class="ui-ai-open" (click)="edit(s)">{{ 'ai.topology.edit' | translate }}</button>
        }
      }
    </mat-dialog-actions>
  `,
  styles: `
    .ui-topology-suggestion-form { display: grid; gap: 8px; margin-bottom: 8px; }
    .ui-ai-result { margin-top: 12px; }
  `,
})
export class TopologySuggestionDialogComponent {
  private readonly ai = inject(AiService);

  private readonly drafts = inject(TopologyDraftStore);

  private readonly router = inject(Router);

  private readonly notifier = inject(Notifier);

  private readonly i18n = inject(I18nService);

  private readonly dialogRef = inject(MatDialogRef<TopologySuggestionDialogComponent>);

  private readonly data = inject<TopologySuggestionData | null>(MAT_DIALOG_DATA, { optional: true });

  protected description = this.data?.description ?? '';

  protected sample = '';

  protected readonly busy = signal(false);

  protected readonly error = signal<string | undefined>(undefined);

  protected readonly suggestion = signal<TopologySuggestion | undefined>(undefined);

  /** Asks the agent. */
  protected suggest(): void {
    this.busy.set(true);
    this.error.set(undefined);
    this.suggestion.set(undefined);
    const sample = normalizePath(this.sample.trim());
    this.ai
      .suggestTopology({ description: this.description.trim(), sample: sample || null, context: this.data?.context ?? null })
      .pipe(finalize(() => this.busy.set(false)))
      .subscribe({
        next: (suggestion) => this.suggestion.set(suggestion),
        error: (error: unknown) =>
          this.error.set(aiErrorMessage(error, (key, params) => this.i18n.t(key, params), (e) => this.notifier.message(e))),
      });
  }

  /**
   * The confidence as a percentage.
   *
   * @param suggestion - the suggestion
   * @returns 0 to 100
   */
  protected confidence(suggestion: TopologySuggestion): number {
    return percent(suggestion.confidence);
  }

  /**
   * Opens an existing topology.
   *
   * @param id - its identifier
   */
  protected open(id: string): void {
    this.dialogRef.close();
    void this.router.navigate(['/topologies', id]);
  }

  /**
   * Opens the draft in the topology editor, where it is reviewed and saved.
   *
   * @param suggestion - the suggestion
   */
  protected edit(suggestion: TopologySuggestion): void {
    if (!suggestion.draft) {
      return;
    }
    this.drafts.offer(completeTopology(suggestion.draft));
    this.dialogRef.close();
    void this.router.navigate(['/topologies', 'new'], { queryParams: { origin: 'ai' } });
  }
}
