import { ChangeDetectionStrategy, Component, computed, effect, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { Router, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { of } from 'rxjs';

import { I18nService } from '../core/i18n/i18n.service';
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
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="ui-breadcrumb" [attr.aria-label]="'breadcrumb.label' | translate">
      <a [routerLink]="['/topologies']">{{ 'nav.topologies' | translate }}</a> /
      <span aria-current="page">{{ isNew() ? ('topology.new' | translate) : draft()?.name }}</span>
    </nav>
    @if (source.isLoading()) {
      <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
    }
    @if (source.error(); as error) {
      <p class="ui-error-text" role="alert">{{ message(error) }}</p>
    }
    @if (draft(); as t) {
      <form class="ui-form ui-topology-editor" (ngSubmit)="save(t)">
        <header class="ui-page-header">
          <h1>{{ isNew() ? ('topology.new' | translate) : t.name }}</h1>
          <div class="ui-row">
            @if (t.builtIn) {
              <span class="ui-pill">{{ 'topology.readOnly' | translate }}</span>
              <a mat-stroked-button [routerLink]="['/topologies', 'new']" [queryParams]="{ from: t.id }">
                <mat-icon aria-hidden="true">content_copy</mat-icon> {{ 'topology.duplicate' | translate }}
              </a>
            } @else {
              <button mat-flat-button color="primary" type="submit" [disabled]="busy()">{{ 'common.save' | translate }}</button>
              @if (!isNew()) {
                <button mat-button type="button" color="warn" (click)="remove(t)" [disabled]="busy()">
                  {{ 'common.delete' | translate }}
                </button>
              }
            }
          </div>
        </header>
        <div class="ui-inline-fields">
          <mat-form-field>
            <mat-label>{{ 'child.identifier' | translate }}</mat-label>
            <input matInput name="id" [(ngModel)]="t.id" required [readonly]="!isNew()" />
          </mat-form-field>
          <mat-form-field>
            <mat-label>{{ 'communities.name' | translate }}</mat-label>
            <input matInput name="name" [(ngModel)]="t.name" required [readonly]="t.builtIn" />
          </mat-form-field>
        </div>
        <mat-form-field>
          <mat-label>{{ 'communities.description' | translate }}</mat-label>
          <textarea matInput name="description" rows="2" [(ngModel)]="t.description" [readonly]="t.builtIn"></textarea>
        </mat-form-field>
        @if (errors().length > 0) {
          <ul class="ui-error-text ui-topology-errors" role="alert">
            @for (error of errors(); track error) {
              <li>{{ error }}</li>
            }
          </ul>
        }
        @if (busy()) {
          <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
        }
        <h2>{{ 'topology.structure' | translate }}</h2>
        <ui-node-editor [node]="t.root" [readonly]="t.builtIn" />
      </form>
    }
  `,
})
export class TopologyEditorPageComponent {
  private readonly service = inject(TopologyService);

  private readonly notifier = inject(Notifier);

  private readonly router = inject(Router);

  private readonly i18n = inject(I18nService);

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
            ? {
                ...clone(source),
                id: `${source.id}-copy`,
                name: this.i18n.t('topology.copyName', { name: source.name }),
                builtIn: false,
              }
            : {
                id: '',
                name: '',
                description: '',
                builtIn: false,
                root: emptyNode(
                  'object',
                  this.i18n.t('topology.objectLabel'),
                  'Object',
                  this.i18n.t('topology.titleLabel'),
                ) as EditableNode,
              },
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
    return this.notifier.message(error);
  }

  /**
   * Validates and stores the topology.
   *
   * @param topology - the edited topology
   */
  protected save(topology: EditableTopology): void {
    const value = topology as unknown as Topology;
    const errors = validateTopology(value, (key, params) => this.i18n.t(key, params));
    this.errors.set(errors);
    if (errors.length > 0) {
      return;
    }
    this.busy.set(true);
    this.service.save(value, !this.isNew()).subscribe({
      next: (saved) => {
        this.busy.set(false);
        this.notifier.success(this.i18n.t('topology.saved'));
        if (this.isNew()) {
          void this.router.navigate(['/topologies', saved.id]);
        }
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error(this.i18n.t('topology.save'), error);
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
        this.notifier.success(this.i18n.t('topology.deleted'));
        void this.router.navigate(['/topologies']);
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error(this.i18n.t('topology.delete'), error);
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
