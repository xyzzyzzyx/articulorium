import { ChangeDetectionStrategy, Component, computed, effect, inject, input, output, signal, untracked } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { Router, RouterLink } from '@angular/router';
import { forkJoin, map, of, switchMap } from 'rxjs';

import { LdpService } from '../core/ldp/ldp.service';
import { ResourceView } from '../core/ldp/models';
import { childPath, parentPath, pathOf, routeTo } from '../core/ldp/paths';
import { addTypesUpdate, replaceValuesUpdate } from '../core/rdf/sparql';
import { ART, LDP, PCDM } from '../core/rdf/vocabulary';
import { ChildRule, FileUse, Topology, TopologyNode, cardinality, findNode, flatten } from '../core/topology/topology';
import { TopologyService } from '../core/topology/topology.service';
import { Notifier } from '../shared/notifier.service';

/** A member or file shown in the panel. */
interface Entry {
  readonly name: string;
  readonly path: string;
  readonly route: string[];
}

/** A file with its PCDM use types. */
interface FileEntry extends Entry {
  readonly uses: readonly string[];
}

/**
 * The topology aside of a resource.
 *
 * <p>For a resource built from a topology it shows the node it instantiates, its parent and
 * members in order, buttons adding the children the topology allows, the node's metadata fields
 * and its kinds of files with upload. For any other container it offers to build a topology in
 * it: choose a topology, a title and how many children of each kind to create.</p>
 */
@Component({
  selector: 'ui-topology-panel',
  imports: [
    FormsModule,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    MatSelectModule,
    RouterLink,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (topologyId(); as id) {
      @if (topology.value(); as t) {
        @if (node(); as n) {
          <section class="ui-topology" aria-label="Topology">
            <p class="ui-chip-row">
              <a class="ui-pill" [routerLink]="['/topologies', t.id]"><mat-icon inline>schema</mat-icon> {{ t.name }}</a>
              <span class="ui-pill">{{ n.label }} · {{ n.pcdmType }}</span>
            </p>
            @if (parentRoute(); as up) {
              <a mat-button [routerLink]="up"><mat-icon>arrow_upward</mat-icon> Parent</a>
            }
            @if (n.children.length > 0) {
              <h3>Members <span class="ui-badge">{{ members().length }}</span></h3>
              @if (members().length > 0) {
                <ol class="ui-tree ui-topology-members">
                  @for (member of members(); track member.path) {
                    <li><a [routerLink]="member.route">{{ member.name }}</a></li>
                  }
                </ol>
              }
              <div class="ui-row">
                @for (rule of n.children; track rule.node.key) {
                  <button mat-stroked-button type="button" [disabled]="busy() || !canAdd(rule)" (click)="addMember(rule)">
                    <mat-icon>add</mat-icon> Add {{ rule.node.label }}
                  </button>
                  <span class="ui-muted">({{ cardinalityOf(rule) }})</span>
                }
              </div>
            }
            @if (n.metadata.length > 0) {
              <h3>Metadata</h3>
              <form class="ui-form ui-topology-metadata" (ngSubmit)="saveMetadata(n)">
                @for (field of n.metadata; track field.predicate) {
                  <mat-form-field>
                    <mat-label>{{ field.label }}{{ field.required ? ' *' : '' }}</mat-label>
                    @if (field.repeatable) {
                      <textarea matInput rows="2" [name]="field.predicate" [(ngModel)]="metadata[field.predicate]"></textarea>
                    } @else {
                      <input matInput [name]="field.predicate" [(ngModel)]="metadata[field.predicate]" />
                    }
                    @if (field.repeatable) {
                      <mat-hint>One value per line</mat-hint>
                    }
                  </mat-form-field>
                }
                <button mat-flat-button color="primary" type="submit" [disabled]="busy() || !writable()">Save metadata</button>
              </form>
            }
            @if (n.files.length > 0) {
              <h3>Files</h3>
              @for (use of n.files; track use.use) {
                <div class="ui-topology-file">
                  <strong>{{ use.label }}</strong>
                  @if (use.mediaTypes.length > 0) {
                    <span class="ui-muted"> ({{ use.mediaTypes.join(', ') }})</span>
                  }
                  <ul class="ui-tree">
                    @for (file of filesOf(use); track file.path) {
                      <li><a [routerLink]="file.route">{{ file.name }}</a></li>
                    } @empty {
                      <li class="ui-muted">None yet</li>
                    }
                  </ul>
                  @if (writable()) {
                    <input type="file" [attr.aria-label]="'Upload ' + use.label" (change)="upload(use, $event)" [disabled]="busy()" />
                  }
                </div>
              }
            }
          </section>
        } @else {
          <p class="ui-muted">The topology {{ t.name }} has no node {{ nodeKey() }}.</p>
        }
      } @else if (topology.error()) {
        <p class="ui-error-text">Topology {{ id }} is not available.</p>
      } @else {
        <mat-progress-bar mode="indeterminate" />
      }
    } @else if (canBuild() && !building() && !expanded()) {
      <button mat-stroked-button type="button" class="ui-topology-start" (click)="building.set(true)">
        <mat-icon>auto_awesome</mat-icon> Build from a topology…
      </button>
    } @else if (canBuild()) {
      <form class="ui-form ui-topology-build" (ngSubmit)="build()">
        <p>Create the PCDM structure of a book, newspaper, journal, postcards or a custom topology in this container.</p>
        @if (summaries.value(); as list) {
          <mat-form-field>
            <mat-label>Topology</mat-label>
            <mat-select name="topology" [ngModel]="selectedId()" (ngModelChange)="selectedId.set($event)">
              @for (summary of list; track summary.id) {
                <mat-option [value]="summary.id">{{ summary.name }}</mat-option>
              }
            </mat-select>
          </mat-form-field>
        } @else if (summaries.error()) {
          <p class="ui-error-text">The topology service is not available.</p>
        } @else {
          <mat-progress-bar mode="indeterminate" />
        }
        <mat-form-field>
          <mat-label>Title</mat-label>
          <input matInput name="title" required [(ngModel)]="title" />
        </mat-form-field>
        @if (selected.value(); as chosen) {
          @for (entry of countable(chosen); track entry.node.key) {
            <mat-form-field>
              <mat-label>Number of {{ entry.node.label }} per {{ entry.parent }}</mat-label>
              <input matInput type="number" min="0" [name]="'count-' + entry.node.key" [(ngModel)]="counts[entry.node.key]" />
            </mat-form-field>
          }
        }
        @if (busy()) {
          <mat-progress-bar mode="indeterminate" />
        }
        <button mat-flat-button color="primary" type="submit" [disabled]="busy() || !selectedId() || !title.trim()">
          <mat-icon>auto_awesome</mat-icon> Build
        </button>
      </form>
    }
  `,
  styles: `
    h3 { margin: 16px 0 4px; font-size: 1rem; }
    .ui-topology-file { margin: 8px 0; }
  `,
})
export class TopologyPanelComponent {
  private readonly ldp = inject(LdpService);

  private readonly topologies = inject(TopologyService);

  private readonly notifier = inject(Notifier);

  private readonly router = inject(Router);

  /** The resource shown. */
  readonly view = input.required<ResourceView>();

  /** Whether the build form is shown right away (otherwise behind a button). */
  readonly expanded = input(false);

  /** Emits after the resource changed (metadata saved, member added, file uploaded). */
  readonly changed = output<void>();

  protected readonly busy = signal(false);

  /** The topology the resource was built from. */
  protected readonly topologyId = computed(() => {
    const view = this.view();
    return view.graph.value(view.subject, ART.topology);
  });

  /** The node the resource instantiates. */
  protected readonly nodeKey = computed(() => {
    const view = this.view();
    return view.graph.value(view.subject, ART.topologyNode);
  });

  protected readonly topology = rxResource({
    params: () => this.topologyId(),
    stream: ({ params }) => this.topologies.get(params),
  });

  protected readonly node = computed<TopologyNode | undefined>(() => {
    const topology = this.topology.value();
    const key = this.nodeKey();
    return topology && key ? findNode(topology.root, key) : undefined;
  });

  /** Members of the resource (PCDM membership), in natural order of their names. */
  protected readonly members = computed<Entry[]>(() => {
    const view = this.view();
    return this.entries(view.graph.objects(view.subject, PCDM.hasMember).map((term) => term.value));
  });

  /** Files of the resource with their uses. */
  protected readonly files = rxResource({
    params: () => {
      const view = this.view();
      return this.topologyId() ? view.graph.objects(view.subject, PCDM.hasFile).map((term) => term.value) : undefined;
    },
    stream: ({ params }) =>
      params.length === 0
        ? of([] as FileEntry[])
        : forkJoin(
            this.entries(params).map((entry) =>
              this.ldp.load(entry.path).pipe(
                map((file) => ({ ...entry, uses: file.graph.types(file.subject) }) satisfies FileEntry),
              ),
            ),
          ),
  });

  /** Route of the instance this resource is a member of. */
  protected readonly parentRoute = computed(() => {
    const parent = parentPath(this.view().path);
    if (parent === undefined || !parent.endsWith('/members')) {
      return undefined;
    }
    const owner = parentPath(parent);
    return owner === undefined ? undefined : routeTo('browse', owner);
  });

  /** Whether the user may change the resource. */
  protected readonly writable = computed(() => {
    const allow = this.view().wacAllow;
    return !allow || allow.user.has('write');
  });

  /** Whether a topology can be built here: a container (not a topology node) the user may append to. */
  protected readonly canBuild = computed(() => {
    const view = this.view();
    const container = view.graph.hasType(view.subject, LDP.container) || view.linkTypes.includes(LDP.container);
    const allow = view.wacAllow;
    return container && !view.binary && (!allow || allow.user.has('append') || allow.user.has('write'));
  });

  /** Whether the build form is open. */
  protected readonly building = signal(false);

  /** Topologies offered for building, loaded once the build form opens. */
  protected readonly summaries = rxResource({
    params: () => (this.canBuild() && !this.topologyId() && (this.building() || this.expanded()) ? true : undefined),
    stream: () => this.topologies.list(),
  });

  protected readonly selectedId = signal<string | undefined>(undefined);

  protected readonly selected = rxResource({
    params: () => this.selectedId(),
    stream: ({ params }) => this.topologies.get(params),
  });

  /** Values of the metadata form, by predicate (repeatable fields: one value per line). */
  protected metadata: Record<string, string> = {};

  protected title = '';

  protected counts: Record<string, number> = {};

  constructor() {
    // fill the metadata form from the resource whenever it (or its node) changes
    effect(() => {
      const node = this.node();
      const view = this.view();
      const values: Record<string, string> = {};
      for (const field of node?.metadata ?? []) {
        values[field.predicate] = view.graph
          .objects(view.subject, field.predicate)
          .map((term) => term.value)
          .join('\n');
      }
      this.metadata = values;
    });
    // the router reuses the panel for another resource: start with the build form closed
    effect(() => {
      if (this.view().path !== undefined) {
        untracked(() => this.building.set(false));
      }
    });
    // default child counts of the chosen topology
    effect(() => {
      const chosen = this.selected.value();
      const counts: Record<string, number> = {};
      if (chosen) {
        for (const entry of flatten(chosen.root)) {
          if (entry.rule) {
            counts[entry.node.key] = entry.rule.initial;
          }
        }
      }
      this.counts = counts;
    });
  }

  /**
   * Lists the nodes of a topology whose count can be chosen.
   *
   * @param topology - the topology
   * @returns the nodes with the label of their parent
   */
  protected countable(topology: Topology): { node: TopologyNode; parent: string }[] {
    return flatten(topology.root)
      .filter((entry) => entry.rule && entry.rule.min !== entry.rule.max)
      .map((entry) => ({
        node: entry.node,
        parent: flatten(topology.root).find((candidate) => candidate.node.children.some((rule) => rule.node === entry.node))
          ?.node.label ?? '',
      }));
  }

  /**
   * Describes a child rule's cardinality.
   *
   * @param rule - the rule
   * @returns for example `1 – ∞`
   */
  protected cardinalityOf(rule: ChildRule): string {
    return cardinality(rule);
  }

  /**
   * Tells whether another child of a kind may be added.
   *
   * @param rule - the rule
   * @returns `true` while below the maximum and writable
   */
  protected canAdd(rule: ChildRule): boolean {
    if (!this.writable() || rule.max < 0) {
      return this.writable();
    }
    // members are only counted per kind when the node has one kind of child; the service
    // enforces the maximum in every case
    return this.node()?.children.length !== 1 || this.members().length < rule.max;
  }

  /**
   * Lists the files with a use.
   *
   * @param use - the file use
   * @returns the files
   */
  protected filesOf(use: FileUse): FileEntry[] {
    return (this.files.value() ?? []).filter((file) => file.uses.includes(use.use));
  }

  /**
   * Adds a child of a kind and opens it.
   *
   * @param rule - the child's rule
   */
  protected addMember(rule: ChildRule): void {
    const id = this.topologyId();
    if (!id) {
      return;
    }
    this.busy.set(true);
    this.topologies.addMember(id, this.view().path, rule.node.key).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.notifier.success(`${rule.node.label} added`);
        void this.router.navigate(routeTo('browse', result.path));
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error(`Add ${rule.node.label}`, error);
      },
    });
  }

  /**
   * Saves the metadata fields of the node.
   *
   * @param node - the node
   */
  protected saveMetadata(node: TopologyNode): void {
    const view = this.view();
    const values: Record<string, string[]> = {};
    const datatypes: Record<string, string | null | undefined> = {};
    for (const field of node.metadata) {
      const raw = this.metadata[field.predicate] ?? '';
      values[field.predicate] = field.repeatable ? raw.split('\n') : [raw];
      datatypes[field.predicate] = field.datatype;
    }
    const update = replaceValuesUpdate(view.subject.value, values, datatypes);
    this.busy.set(true);
    this.ldp.patch(view.url, update, view.etag).subscribe({
      next: () => {
        this.busy.set(false);
        this.notifier.success('Metadata saved');
        this.changed.emit();
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error('Save metadata', error);
      },
    });
  }

  /**
   * Uploads a file of a kind into the resource's `files` container and records its use.
   *
   * @param use - the file use
   * @param event - the change event of the file input
   */
  protected upload(use: FileUse, event: Event): void {
    const input = event.target as HTMLInputElement;
    const file = input.files?.[0];
    if (!file) {
      return;
    }
    this.busy.set(true);
    this.ldp
      .createChild(childPath(this.view().path, 'files'), {
        kind: 'binary',
        file,
        filename: file.name,
        mimeType: file.type || 'application/octet-stream',
      })
      .pipe(switchMap((location) => this.ldp.patch(`${location}/description`, addTypesUpdate(location, [PCDM.file, use.use]))))
      .subscribe({
        next: () => {
          this.busy.set(false);
          input.value = '';
          this.notifier.success(`${use.label} uploaded`);
          this.changed.emit();
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.notifier.error(`Upload ${use.label}`, error);
        },
      });
  }

  /** Builds the chosen topology in this container and opens the result. */
  protected build(): void {
    const id = this.selectedId();
    if (!id) {
      return;
    }
    this.busy.set(true);
    this.topologies.build(id, { parent: this.view().path, title: this.title.trim(), counts: this.counts }).subscribe({
      next: (result) => {
        this.busy.set(false);
        this.notifier.success(`Built ${result.resources.length} resources`);
        void this.router.navigate(routeTo('browse', result.path));
      },
      error: (error: unknown) => {
        this.busy.set(false);
        this.notifier.error('Build', error);
      },
    });
  }

  private entries(iris: readonly string[]): Entry[] {
    const base = this.ldp.base();
    return iris
      .map((iri) => pathOf(base, iri))
      .filter((path): path is string => path !== undefined)
      .map((path) => ({ name: path.split('/').pop() ?? path, path, route: routeTo('browse', path) }))
      .sort((a, b) => a.name.localeCompare(b.name, undefined, { numeric: true }));
  }
}
