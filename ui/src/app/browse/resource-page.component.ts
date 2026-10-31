import { DatePipe } from '@angular/common';
import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatChipsModule } from '@angular/material/chips';
import { MatDialog } from '@angular/material/dialog';
import { MatExpansionModule } from '@angular/material/expansion';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatTooltipModule } from '@angular/material/tooltip';
import { Router, RouterLink } from '@angular/router';
import { filter, switchMap } from 'rxjs';

import { LdpService } from '../core/ldp/ldp.service';
import { ResourceView } from '../core/ldp/models';
import { normalizePath, parentPath, pathOf, routeTo } from '../core/ldp/paths';
import { errorMessage } from '../core/ldp/problem';
import { compact } from '../core/rdf/graph';
import { ART, FEDORA, LDP, PCDM } from '../core/rdf/vocabulary';
import { allows } from '../core/webac/webac';
import { ConfigService } from '../core/config.service';
import { TopologyPanelComponent } from '../topology/topology-panel.component';
import { BreadcrumbComponent } from '../shared/breadcrumb.component';
import { ConfirmDialogComponent, ConfirmData } from '../shared/confirm-dialog.component';
import { Notifier } from '../shared/notifier.service';
import { PropertyTableComponent } from '../shared/property-table.component';
import { BinaryContentComponent } from './binary-content.component';
import { CreateChildFormComponent } from './create-child-form.component';
import { SparqlUpdateFormComponent } from './sparql-update-form.component';

/** A child shown in the children list. */
interface Child {
  readonly iri: string;
  readonly name: string;
  readonly route?: string[];
}

/**
 * The view of one LDP resource, at feature parity with Fedora Commons' HTML view: breadcrumb,
 * creation and modification details, children, properties and the other subjects of the
 * representation, plus the actions the platform allows (create child, update properties,
 * download/update/fixity of binaries, versioning and delete), archival group membership, access
 * control (permissions), notification inboxes, IIIF export of PCDM structures and the topology
 * aside that builds and navigates PCDM structures.
 */
@Component({
  selector: 'ui-resource-page',
  imports: [
    BinaryContentComponent,
    BreadcrumbComponent,
    CreateChildFormComponent,
    DatePipe,
    MatButtonModule,
    MatCardModule,
    MatChipsModule,
    MatExpansionModule,
    MatIconModule,
    MatProgressBarModule,
    MatTooltipModule,
    PropertyTableComponent,
    RouterLink,
    SparqlUpdateFormComponent,
    TopologyPanelComponent,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  templateUrl: './resource-page.component.html',
  styleUrl: './resource-page.component.scss',
})
export class ResourcePageComponent {
  private readonly ldp = inject(LdpService);

  private readonly router = inject(Router);

  private readonly dialog = inject(MatDialog);

  private readonly notifier = inject(Notifier);

  private readonly config = inject(ConfigService);

  /** Path of the resource (bound from the route); empty for the root container. */
  readonly path = input('');

  /** The normalised path. */
  protected readonly resourcePath = computed(() => normalizePath(this.path()));

  /** The resource, reloaded after every change. */
  protected readonly resource = rxResource({
    params: () => this.resourcePath(),
    stream: ({ params }) => this.ldp.load(params),
  });

  /** {@code art:topology}, used by the template. */
  protected readonly artTopology = ART.topology;

  /** The platform base. */
  protected readonly base = computed(() => this.ldp.base());

  /** Message of a failed load. */
  protected readonly loadError = computed(() => {
    const error = this.resource.error();
    return error ? errorMessage(error) : undefined;
  });

  /** The children of a container, sorted by name. */
  protected readonly children = computed<Child[]>(() => {
    const view = this.view();
    if (!view) {
      return [];
    }
    return view.graph
      .objects(view.subject, LDP.contains)
      .filter((term) => term.kind === 'iri')
      .map((term) => {
        const path = pathOf(this.base(), term.value);
        return {
          iri: term.value,
          name: path?.split('/').pop() ?? term.value,
          route: path === undefined ? undefined : routeTo('browse', path),
        };
      })
      .sort((a, b) => a.name.localeCompare(b.name));
  });

  /** Types of the resource as prefixed names. */
  protected readonly types = computed(() => {
    const view = this.view();
    return view ? view.graph.types(view.subject).map((type) => ({ iri: type, name: compact(type) ?? type })) : [];
  });

  /** Creation and modification details. */
  protected readonly audit = computed(() => {
    const view = this.view();
    if (!view) {
      return undefined;
    }
    const { graph, subject } = view;
    return {
      created: graph.value(subject, FEDORA.created),
      createdBy: graph.value(subject, FEDORA.createdBy),
      modified: graph.value(subject, FEDORA.lastModified),
      modifiedBy: graph.value(subject, FEDORA.lastModifiedBy),
    };
  });

  /** Subjects of the representation other than the resource (blank nodes, hash IRIs...). */
  protected readonly otherSubjects = computed(() => {
    const view = this.view();
    if (!view) {
      return [];
    }
    return view.graph
      .subjects()
      .filter((subject) => subject.kind !== view.subject.kind || subject.value !== view.subject.value)
      .map((subject) => ({ subject, title: view.graph.title(subject) }));
  });

  /** Whether the resource is a container (may have children). */
  protected readonly isContainer = computed(() => {
    const view = this.view();
    return !!view && (view.graph.hasType(view.subject, LDP.container) || view.linkTypes.includes(LDP.container));
  });

  /** The loaded resource, or `undefined` while loading or after an error. */
  protected readonly view = computed<ResourceView | undefined>(() =>
    this.resource.hasValue() ? this.resource.value() : undefined,
  );

  /** Title of the resource. */
  protected readonly title = computed(() => {
    const view = this.view();
    if (!view) {
      return this.resourcePath() || 'Root';
    }
    return view.path === '' && !view.graph.value(view.subject, 'http://purl.org/dc/terms/title')
      ? 'Root'
      : view.graph.title(view.subject);
  });

  /** Path of the archival group a member belongs to. */
  protected readonly archivalGroupRoute = computed(() => {
    const iri = this.view()?.archivalGroupIri;
    const path = iri ? pathOf(this.base(), iri) : undefined;
    return path === undefined ? undefined : routeTo('browse', path);
  });

  /** Whether versions can be created here (not for members of an archival group). */
  protected readonly versionable = computed(() => {
    const view = this.view();
    return !!view && view.path !== '' && !view.archivalGroupIri && allows(view.wacAllow, 'write');
  });

  /** Whether the user may change the resource. */
  protected readonly writable = computed(() => allows(this.view()?.wacAllow, 'write'));

  /** Whether the user may add children. */
  protected readonly appendable = computed(() => allows(this.view()?.wacAllow, 'append'));

  /** The IIIF export of a PCDM object or collection, or `undefined` for other resources. */
  protected readonly iiifUrl = computed(() => {
    const view = this.view();
    if (!view) {
      return undefined;
    }
    const exportable = [PCDM.object, PCDM.collection, ART.community, ART.collection, ART.communityRoot].some((type) =>
      view.graph.hasType(view.subject, type),
    );
    return exportable ? `${this.config.transformBase()}iiif/${view.path}` : undefined;
  });

  /** Target of SPARQL updates: the resource, or a binary's description. */
  protected readonly updateTarget = computed(() => {
    const view = this.view();
    return view?.binary?.descriptionUrl ?? view?.url ?? '';
  });

  /** Whether the representation may be patched (for binaries: their description). */
  protected readonly patchable = computed(() => {
    const view = this.view();
    return !!view && (view.binary ? true : view.allow.has('PATCH')) && allows(view.wacAllow, 'write');
  });

  /**
   * Route commands of another view of this resource.
   *
   * @param view - for example `versions`
   * @returns the commands
   */
  protected routeOf(view: string): string[] {
    return routeTo(view, this.resourcePath());
  }

  /** Reloads the resource. */
  protected reload(): void {
    this.resource.reload();
  }

  /**
   * Opens a newly created child.
   *
   * @param location - its URL
   */
  protected openCreated(location: string): void {
    const path = pathOf(this.base(), location);
    if (path === undefined) {
      this.reload();
    } else {
      void this.router.navigate(routeTo('browse', path));
    }
  }

  /** Records the current state as a version and shows the version list. */
  protected createVersion(): void {
    this.ldp.createVersion(this.resourcePath()).subscribe({
      next: () => {
        this.notifier.success('Version created');
        void this.router.navigate(this.routeOf('versions'));
      },
      error: (error: unknown) => this.notifier.error('Create version', error),
    });
  }

  /** Deletes the resource after confirmation and returns to its parent. */
  protected delete(): void {
    const data: ConfirmData = {
      title: 'Delete resource',
      message: `Delete ${this.title()}${this.isContainer() ? ' and everything it contains' : ''}? This cannot be undone.`,
      confirm: 'Delete',
    };
    this.dialog
      .open(ConfirmDialogComponent, { data })
      .afterClosed()
      .pipe(
        filter((confirmed) => confirmed === true),
        switchMap(() => this.ldp.delete(this.resourcePath())),
      )
      .subscribe({
        next: () => {
          this.notifier.success('Resource deleted');
          void this.router.navigate(routeTo('browse', parentPath(this.resourcePath()) ?? ''));
        },
        error: (error: unknown) => this.notifier.error('Delete', error),
      });
  }
}
