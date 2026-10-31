import { ChangeDetectionStrategy, Component, inject } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';

import { errorMessage } from '../core/ldp/problem';
import { TopologyService } from '../core/topology/topology.service';

/**
 * Lists the topologies: the built-in ones (Book, Newspaper, Journal, Postcards, Manuscript,
 * Audiovisual recording) and the custom ones, with a button to create a new topology.
 */
@Component({
  selector: 'ui-topology-list-page',
  imports: [MatButtonModule, MatCardModule, MatIconModule, MatProgressBarModule, RouterLink],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <header class="ui-page-header">
      <h1>Topologies</h1>
      <a mat-flat-button color="primary" [routerLink]="['/topologies', 'new']"><mat-icon>add</mat-icon> New topology</a>
    </header>
    <p>
      A topology describes the PCDM structure of a kind of digital object: its levels (collections and objects), their
      metadata fields, their kinds of files and how many children each level has. Build one in any container from its
      Topology panel, or in a collection from the Communities page.
    </p>
    @if (topologies.isLoading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    @if (topologies.error(); as error) {
      <p class="ui-error-text">{{ message(error) }}</p>
    }
    <div class="ui-card-grid ui-topology-list">
      @for (topology of topologies.value() ?? []; track topology.id) {
        <mat-card appearance="outlined" class="ui-topology-summary">
          <mat-card-header>
            <mat-icon mat-card-avatar>{{ topology.builtIn ? 'verified' : 'tune' }}</mat-icon>
            <mat-card-title><a [routerLink]="['/topologies', topology.id]">{{ topology.name }}</a></mat-card-title>
            <mat-card-subtitle>{{ topology.builtIn ? 'Built-in' : 'Custom' }} · {{ topology.depth }} levels</mat-card-subtitle>
          </mat-card-header>
          <mat-card-content><p>{{ topology.description }}</p></mat-card-content>
        </mat-card>
      }
    </div>
  `,
})
export class TopologyListPageComponent {
  private readonly service = inject(TopologyService);

  protected readonly topologies = rxResource({ stream: () => this.service.list() });

  /**
   * Formats a load failure.
   *
   * @param error - the failure
   * @returns the message
   */
  protected message(error: unknown): string {
    return errorMessage(error);
  }
}
