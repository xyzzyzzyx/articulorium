import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatDialog } from '@angular/material/dialog';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { TopologySuggestionDialogComponent } from '../ai/topology-suggestion-dialog.component';
import { AiService } from '../core/ai/ai.service';
import { PluralPipe } from '../core/i18n/format.pipes';
import { I18nService } from '../core/i18n/i18n.service';
import { TopologyService } from '../core/topology/topology.service';
import { Notifier } from '../shared/notifier.service';

/**
 * Lists the topologies: the built-in ones (Book, Newspaper, Journal, Postcards, Manuscript,
 * Audiovisual recording) and the custom ones, with a button to create a new topology and, when the
 * AI agent offers it, one to ask it which topology fits some material.
 */
@Component({
  selector: 'ui-topology-list-page',
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatProgressBarModule, PluralPipe, RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="ui-page-header">
      <h1>{{ 'nav.topologies' | translate }}</h1>
      <div class="ui-row">
        @if (suggestions()) {
          <button mat-stroked-button class="ui-ai-topology" (click)="suggest()">
            <mat-icon aria-hidden="true">auto_awesome</mat-icon> {{ 'ai.topology.open' | translate }}
          </button>
        }
        <a mat-flat-button color="primary" [routerLink]="['/topologies', 'new']">
          <mat-icon aria-hidden="true">add</mat-icon> {{ 'topology.new' | translate }}
        </a>
      </div>
    </header>
    <p>{{ 'topology.intro' | translate }}</p>
    @if (topologies.isLoading()) {
      <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
    }
    @if (topologies.error(); as error) {
      <p class="ui-error-text" role="alert">{{ message(error) }}</p>
    }
    <div class="ui-card-grid ui-topology-list">
      @for (topology of topologies.value() ?? []; track topology.id) {
        <mat-card appearance="outlined" class="ui-topology-summary">
          <mat-card-header>
            <mat-icon mat-card-avatar aria-hidden="true">{{ topology.builtIn ? 'verified' : 'tune' }}</mat-icon>
            <mat-card-title><a [routerLink]="['/topologies', topology.id]">{{ topology.name }}</a></mat-card-title>
            <mat-card-subtitle>
              {{ (topology.builtIn ? 'topology.builtIn' : 'topology.custom') | translate }} ·
              {{ 'topology.levels' | tplural: topology.depth }}
            </mat-card-subtitle>
          </mat-card-header>
          <mat-card-content><p>{{ topology.description }}</p></mat-card-content>
        </mat-card>
      }
    </div>
  `,
})
export class TopologyListPageComponent {
  private readonly service = inject(TopologyService);

  private readonly notifier = inject(Notifier);

  private readonly i18n = inject(I18nService);

  private readonly dialog = inject(MatDialog);

  /** Whether the AI agent suggests topologies. */
  protected readonly suggestions = inject(AiService).offers('topology');

  // built-in topologies are translated by the topology service: load them again when the
  // language changes
  protected readonly topologies = rxResource({ params: () => this.i18n.locale(), stream: () => this.service.list() });

  /** Opens the AI agent's topology suggestion. */
  protected suggest(): void {
    this.dialog.open(TopologySuggestionDialogComponent, { width: '640px', maxWidth: '95vw' });
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
}
