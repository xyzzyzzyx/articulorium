import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { PluralPipe } from '../core/i18n/format.pipes';
import { TopologyService } from '../core/topology/topology.service';
import { Notifier } from '../shared/notifier.service';

/**
 * Lists the topologies: the built-in ones (Book, Newspaper, Journal, Postcards, Manuscript,
 * Audiovisual recording) and the custom ones, with a button to create a new topology.
 */
@Component({
  selector: 'ui-topology-list-page',
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatProgressBarModule, PluralPipe, RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="ui-page-header">
      <h1>{{ 'nav.topologies' | translate }}</h1>
      <a mat-flat-button color="primary" [routerLink]="['/topologies', 'new']">
        <mat-icon aria-hidden="true">add</mat-icon> {{ 'topology.new' | translate }}
      </a>
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

  protected readonly topologies = rxResource({ stream: () => this.service.list() });

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
