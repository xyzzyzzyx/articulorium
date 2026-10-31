import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router, RouterLink } from '@angular/router';
import { of } from 'rxjs';

import { errorMessage } from '../core/ldp/problem';
import { Topology, emptyNode, validateTopology } from '../core/topology/topology';
import { TopologyService } from '../core/topology/topology.service';
import { Notifier } from '../shared/notifier.service';
import { EditableNode, NodeEditorComponent } from './node-editor.component';

/** A topology as edited. */
interface EditableTopology {
  id: string;
  name: string;
  description: string;
  builtIn: boolean;
  root: EditableNode;
}

/**
 * Shows or edits one topology. Built-in topologies are read-only and can be duplicated; custom
 * topologies can be changed and deleted. `new` creates a topology, optionally copying another
 * (`?from=<id>`).
 */
@Component({
  selector: 'ui-topology-editor-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    NodeEditorComponent,
    RouterLink,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="ui-breadcrumb" aria-label="Breadcrumb">
      <a [routerLink]="['/topologies']">Topologies</a> / <span>{{ isNew() ? 'New topology' : draft()?.name }}</span>
    </nav>
    @if (source.isLoading()) {
      <mat-progress-bar mode="indeterminate" />
    }
    @if (source.error(); as error) {
      <p class="ui-error-text">{{ message(error) }}</p>
    }
    @if (draft(); as t) {
      <form class="ui-form ui-topology-editor" (ngSubmit)="save(t)">
        <header class="ui-page-header">
          <h1>{{ isNew() ? 'New topology' : t.name }}</h1>
          <div class="ui-row">
            @if (t.builtIn) {
              <span class="ui-pill">Built-in (read-only)</span>
              <a mat-stroked-button [routerLink]="['/topologies', 'new']" [queryParams]="{ from: t.id }">
                <mat-icon>content_copy</mat-icon> Duplicate
              </a>
            } @else {
              <button mat-flat-button color="primary" type="submit" [disabled]="busy()">Save</button>
              @if (!isNew()) {
                <button mat-button type="button" color="warn" (click)="remove(t)" [disabled]="busy()">Delete</button>
              }
            }
          </div>
        </header>
        <div class="ui-inline-fields">
          <mat-form-field>
            <mat-label>Identifier</mat-label>
            <input matInput name="id" [(ngModel)]="t.id" required [readonly]="!isNew()" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>Name</mat-label>
            <input matInput name="name" [(ngModel)]="t.name" required [readonly]="t.builtIn" />
          </mat-form-field>
        </div>
        <mat-form-field>
          <mat-label>Description</mat-label>
          <textarea matInput name="description" rows="2" [(ngModel)]="t.description" [readonly]="t.builtIn"></textarea>
        </mat-form-field>
        @if (errors().length > 0) {
          <ul class="ui-error-text ui-topology-errors">
            @for (error of errors(); track error) {
              <li>{{ error }}</li>
            }
          </ul>
        }
        @if (busy()) {
          <mat-progress-bar mode="indeterminate" />
        }
        <h2>Structure</h2>
        <ui-node-editor [node]="t.root" [readonly]="t.builtIn" />
      </form>
    }
  `,
})
export class TopologyEditorPageComponent {
  private readonly service = inject(TopologyService);

  private readonly notifier = inject(Notifier);

  private readonly router = inject(Router);

  /** The topology identifier, or `new` (bound from the route). */
  readonly id = input('new');

  /** The topology a new one starts as a copy of (query parameter). */
  readonly from = input<string | undefined>(undefined);

  protected readonly isNew = computed(() => this.id() === 'new');

  protected readonly source = rxResource({
    params: () => (this.isNew() ? (this.from() ?? '') : this.id()),
    stream: ({ params }) => (params ? this.service.get(params) : of(undefined)),
  });

  protected readonly draft = signal<EditableTopology | undefined>(undefined);

  protected readonly errors = signal<string[]>([]);

  protected readonly busy = signal(false);

  constructor() {
    effect(() => {
      if (this.source.isLoading()) {
        return;
      }
      const source = this.source.value();
      if (this.isNew()) {
        this.draft.set(
          source
            ? { ...clone(source), id: `${source.id}-copy`, name: `${source.name} (copy)`, builtIn: false }
            : { id: '', name: '', description: '', builtIn: false, root: emptyNode('object', 'Object') as EditableNode },
        );
      } else if (source) {
        this.draft.set(clone(source));
      }
      this.errors.set([]);
    });
  }

  /**
   * Formats a failure.
   *
   * @param error - the failure
   * @returns the message
   */
  protected message(error: unknown): string {
    return errorMessage(error);
  }

  /**
   * Validates and stores the topology.
   *
   * @param topology - the edited topology
   */
  protected save(topology: EditableTopology): void {
    const value = topology as unknown as Topology;
    const errors = validateTopology(value);
    this.errors.set(errors);
    if (errors.length > 0) {
      return;
    }
    this.busy.set(true);
    this.service.save(value, !this.isNew()).subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.notifier.success('Topology saved');
        if (this.isNew()) {
          void this.router.navigate(['/topologies', saved.id]);
        }
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error('Save topology', error);
      },
    });
  }

  /**
   * Deletes the topology (resources built from it stay).
   *
   * @param topology - the topology
   */
  protected remove(topology: EditableTopology): void {
    this.busy.set(true);
    this.service.delete(topology.id).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifier.success('Topology deleted');
        void this.router.navigate(['/topologies']);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error('Delete topology', error);
      },
    });
  }
}

/**
 * Copies a topology into mutable plain objects.
 *
 * @param topology - the topology
 * @returns the editable copy
 */
function clone(topology: Topology): EditableTopology {
  return JSON.parse(JSON.stringify({ ...topology, description: topology.description ?? '' })) as EditableTopology;
}
