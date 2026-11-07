import { ChangeDetectionStrategy, Component, computed, inject, input, signal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { FormsModule } from '@angular/forms';
import { MatButtonModule } from '@angular/material/button';
import { MatCardModule } from '@angular/material/card';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatInputModule } from '@angular/material/input';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { Observable, catchError, forkJoin, map, of, switchMap } from 'rxjs';

import { ConfigService } from '../core/config.service';
import { I18nService } from '../core/i18n/i18n.service';
import { LdpService } from '../core/ldp/ldp.service';
import { ResourceView } from '../core/ldp/models';
import { normalizePath, pathOf, routeTo, segmentsOf } from '../core/ldp/paths';
import { DCTERMS, LDP } from '../core/rdf/vocabulary';
import { allows } from '../core/webac/webac';
import { Notifier } from '../shared/notifier.service';
import { TopicSuggestionComponent } from '../ai/topic-suggestion.component';
import { TopologyPanelComponent } from '../topology/topology-panel.component';
import { StructureKind, slugOf, structureKind, structureTurtle } from './communities';

/** A child shown on the page. */
interface Entry {
  readonly path: string;
  readonly title: string;
  readonly description?: string;
  readonly kind: StructureKind;
  readonly route: string[];
}

/** The node shown, with its classified children. */
interface Node {
  readonly view: ResourceView;
  readonly kind: StructureKind;
  readonly communities: readonly Entry[];
  readonly collections: readonly Entry[];
  readonly items: readonly Entry[];
}

/**
 * Navigates the community / sub-community / collection structure stored in the repository below
 * the community container (`/communities` by default).
 *
 * <ul>
 *   <li>The top level lists communities and creates new ones.</li>
 *   <li>A community lists its sub-communities and collections and creates both.</li>
 *   <li>A collection lists its items and builds new items from topologies (book, newspaper...).</li>
 * </ul>
 *
 * Everything is plain LDP: communities are containers typed `art:Community`, collections
 * containers typed `art:Collection` and `pcdm:Collection`, items PCDM objects.
 */
@Component({
  selector: 'ui-communities-page',
  imports: [
    FormsModule,
    MatButtonModule,
    MatCardModule,
    MatFormFieldModule,
    MatIconModule,
    MatInputModule,
    MatProgressBarModule,
    RouterLink,
    TopicSuggestionComponent,
    TopologyPanelComponent,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <nav class="ui-breadcrumb ui-communities-breadcrumb" [attr.aria-label]="'breadcrumb.label' | translate">
      <a [routerLink]="['/communities']"><mat-icon inline aria-hidden="true">corporate_fare</mat-icon> {{ 'nav.communities' | translate }}</a>
      @for (crumb of crumbs(); track crumb.route.join('/')) {
        / <a [routerLink]="crumb.route">{{ crumb.name }}</a>
      }
    </nav>
    @if (node.isLoading()) {
      <mat-progress-bar mode="indeterminate" [attr.aria-label]="'common.loading' | translate" />
    }
    @if (loadError(); as message) {
      <p class="ui-error-text" role="alert">{{ message }}</p>
    }
    @if (node.value(); as n) {
      <header class="ui-page-header">
        <div>
          <h1>{{ heading(n) }}</h1>
          @if (n.view.graph.text(n.view.subject, descriptionPredicate, i18n.locale()); as description) {
            <p class="ui-muted">{{ description }}</p>
          }
          <span class="ui-pill ui-structure-kind">{{ kindLabel(n.kind) }}</span>
        </div>
        <div class="ui-row">
          <a mat-stroked-button [routerLink]="browseRoute(n.view.path)">
            <mat-icon aria-hidden="true">folder_open</mat-icon> {{ 'communities.open' | translate }}
          </a>
          <a mat-stroked-button [routerLink]="routeTo('permissions', n.view.path)">
            <mat-icon aria-hidden="true">admin_panel_settings</mat-icon> {{ 'resource.permissions' | translate }}
          </a>
          <a mat-stroked-button [routerLink]="routeTo('inbox', n.view.path)">
            <mat-icon aria-hidden="true">inbox</mat-icon> {{ 'resource.notifications' | translate }}
          </a>
          <a mat-stroked-button [href]="iiif(n.view.path)" target="_blank" rel="noopener">
            <mat-icon aria-hidden="true">photo_library</mat-icon> {{ 'resource.iiif' | translate }}
          </a>
        </div>
      </header>

      @if (n.kind === 'community' || n.kind === 'collection') {
        <ui-topic-suggestion [view]="n.view" (applied)="node.reload()" />
      }
      @if (n.kind === 'root' || n.kind === 'community') {
        <h2>{{ (n.kind === 'root' ? 'nav.communities' : 'communities.subCommunities') | translate }}</h2>
        <div class="ui-card-grid ui-communities-list">
          @for (entry of n.communities; track entry.path) {
            <mat-card appearance="outlined" class="ui-structure-card">
              <mat-card-header>
                <mat-icon mat-card-avatar aria-hidden="true">groups</mat-icon>
                <mat-card-title><a [routerLink]="entry.route">{{ entry.title }}</a></mat-card-title>
              </mat-card-header>
              @if (entry.description) {
                <mat-card-content><p>{{ entry.description }}</p></mat-card-content>
              }
            </mat-card>
          } @empty {
            <p class="ui-muted">{{ 'communities.noneYet' | translate }}</p>
          }
        </div>
      }
      @if (n.kind === 'community') {
        <h2>{{ 'communities.collections' | translate }}</h2>
        <div class="ui-card-grid ui-collections-list">
          @for (entry of n.collections; track entry.path) {
            <mat-card appearance="outlined" class="ui-structure-card">
              <mat-card-header>
                <mat-icon mat-card-avatar aria-hidden="true">collections_bookmark</mat-icon>
                <mat-card-title><a [routerLink]="entry.route">{{ entry.title }}</a></mat-card-title>
              </mat-card-header>
              @if (entry.description) {
                <mat-card-content><p>{{ entry.description }}</p></mat-card-content>
              }
            </mat-card>
          } @empty {
            <p class="ui-muted">{{ 'communities.noneYet' | translate }}</p>
          }
        </div>
      }
      @if (n.kind === 'collection') {
        <h2>{{ 'communities.items' | translate }}</h2>
        <ul class="ui-tree ui-items-list">
          @for (entry of n.items; track entry.path) {
            <li><mat-icon inline aria-hidden="true">description</mat-icon> <a [routerLink]="entry.route">{{ entry.title }}</a></li>
          } @empty {
            <li class="ui-muted">{{ 'communities.noneYet' | translate }}</li>
          }
        </ul>
      }

      @if (canCreate(n)) {
        <div class="ui-card-grid">
          @if (n.kind === 'root' || n.kind === 'community') {
            <mat-card appearance="outlined">
              <mat-card-header>
                <mat-card-title>{{ (n.kind === 'root' ? 'communities.newCommunity' : 'communities.newSubCommunity') | translate }}</mat-card-title>
              </mat-card-header>
              <mat-card-content>
                <form class="ui-form ui-new-community" (ngSubmit)="create(n, 'community')">
                  <mat-form-field>
                    <mat-label>{{ 'communities.name' | translate }}</mat-label>
                    <input matInput name="communityTitle" required [(ngModel)]="communityTitle" />
                  </mat-form-field>
                  <mat-form-field>
                    <mat-label>{{ 'communities.description' | translate }}</mat-label>
                    <textarea matInput name="communityDescription" rows="2" [(ngModel)]="communityDescription"></textarea>
                  </mat-form-field>
                  <button mat-flat-button color="primary" type="submit" [disabled]="busy() || !communityTitle.trim()">
                    {{ 'common.create' | translate }}
                  </button>
                </form>
              </mat-card-content>
            </mat-card>
          }
          @if (n.kind === 'community') {
            <mat-card appearance="outlined">
              <mat-card-header><mat-card-title>{{ 'communities.newCollection' | translate }}</mat-card-title></mat-card-header>
              <mat-card-content>
                <form class="ui-form ui-new-collection" (ngSubmit)="create(n, 'collection')">
                  <mat-form-field>
                    <mat-label>{{ 'communities.name' | translate }}</mat-label>
                    <input matInput name="collectionTitle" required [(ngModel)]="collectionTitle" />
                  </mat-form-field>
                  <mat-form-field>
                    <mat-label>{{ 'communities.description' | translate }}</mat-label>
                    <textarea matInput name="collectionDescription" rows="2" [(ngModel)]="collectionDescription"></textarea>
                  </mat-form-field>
                  <button mat-flat-button color="primary" type="submit" [disabled]="busy() || !collectionTitle.trim()">
                    {{ 'common.create' | translate }}
                  </button>
                </form>
              </mat-card-content>
            </mat-card>
          }
          @if (n.kind === 'collection') {
            <mat-card appearance="outlined">
              <mat-card-header><mat-card-title>{{ 'communities.newItem' | translate }}</mat-card-title></mat-card-header>
              <mat-card-content>
                <ui-topology-panel [view]="n.view" [expanded]="true" />
              </mat-card-content>
            </mat-card>
          }
        </div>
      }
    }
  `,
})
export class CommunitiesPageComponent {
  private readonly ldp = inject(LdpService);

  private readonly config = inject(ConfigService);

  private readonly notifier = inject(Notifier);

  protected readonly i18n = inject(I18nService);

  /** Path below the community container (bound from the route). */
  readonly path = input('');

  protected readonly descriptionPredicate = DCTERMS.description;

  protected readonly routeTo = routeTo;

  protected readonly busy = signal(false);

  protected communityTitle = '';

  protected communityDescription = '';

  protected collectionTitle = '';

  protected collectionDescription = '';

  /** The repository path of the node shown. */
  protected readonly nodePath = computed(() => {
    const below = normalizePath(this.path());
    const root = this.config.communitiesPath();
    return below ? `${root}/${below}` : root;
  });

  protected readonly crumbs = computed(() => {
    const segments = segmentsOf(normalizePath(this.path()));
    return segments.map((name, index) => ({ name, route: ['/', 'communities', ...segments.slice(0, index + 1)] }));
  });

  protected readonly node = rxResource({
    params: () => this.nodePath(),
    stream: ({ params }) =>
      this.ldp.load(params).pipe(
        switchMap((view) => {
          const children = view.graph
            .objects(view.subject, LDP.contains)
            .map((term) => pathOf(this.ldp.base(), term.value))
            .filter((path): path is string => path !== undefined);
          const entries$: Observable<Entry[]> =
            children.length === 0
              ? of([])
              : forkJoin(
                  children.map((child) => this.entry(child).pipe(catchError(() => of<Entry | undefined>(undefined)))),
                ).pipe(map((entries) => entries.filter((entry): entry is Entry => entry !== undefined)));
          return entries$.pipe(
            map((entries): Node => {
              const sorted = [...entries].sort((a, b) =>
                a.title.localeCompare(b.title, this.i18n.locale(), { numeric: true }),
              );
              const kind: StructureKind =
                view.path === this.config.communitiesPath() ? 'root' : structureKind(view.graph, view.subject);
              return {
                view,
                kind,
                communities: sorted.filter((entry) => entry.kind === 'community'),
                collections: sorted.filter((entry) => entry.kind === 'collection'),
                items: sorted.filter((entry) => entry.kind === 'item'),
              };
            }),
          );
        }),
      ),
  });

  protected readonly loadError = computed(() => {
    const error = this.node.error();
    return error ? this.notifier.message(error) : undefined;
  });

  /**
   * The heading of a node.
   *
   * @param node - the node
   * @returns its title
   */
  protected heading(node: Node): string {
    return node.kind === 'root' ? this.i18n.t('nav.communities') : node.view.graph.title(node.view.subject, this.i18n.locale());
  }

  /**
   * Names a kind.
   *
   * @param kind - the kind
   * @returns the label
   */
  protected kindLabel(kind: StructureKind): string {
    return this.i18n.t(`communities.kind.${kind}`);
  }

  /**
   * Tells whether the user may create children of a node.
   *
   * @param node - the node
   * @returns `true` when allowed
   */
  protected canCreate(node: Node): boolean {
    return node.kind !== 'item' && node.kind !== 'other' && allows(node.view.wacAllow, 'append');
  }

  /**
   * The browse route of a path.
   *
   * @param path - the path
   * @returns the route
   */
  protected browseRoute(path: string): string[] {
    return routeTo('browse', path);
  }

  /**
   * The IIIF Collection of a node.
   *
   * @param path - the node's path
   * @returns the URL
   */
  protected iiif(path: string): string {
    return `${this.config.transformBase()}iiif/${path}`;
  }

  /**
   * Creates a community (sub-community) or collection in a node.
   *
   * @param node - the parent
   * @param kind - what to create
   */
  protected create(node: Node, kind: 'community' | 'collection'): void {
    const title = (kind === 'community' ? this.communityTitle : this.collectionTitle).trim();
    const description = kind === 'community' ? this.communityDescription : this.collectionDescription;
    this.busy.set(true);
    this.ldp
      .createChild(node.view.path, { kind: 'basic', slugHint: slugOf(title) || undefined, turtle: structureTurtle(kind, title, description) })
      .subscribe({
        next: () => {
          this.busy.set(false);
          this.notifier.success(
            this.i18n.t(
              kind === 'collection'
                ? 'communities.collectionCreated'
                : node.kind === 'root'
                  ? 'communities.communityCreated'
                  : 'communities.subCommunityCreated',
            ),
          );
          if (kind === 'community') {
            this.communityTitle = '';
            this.communityDescription = '';
          } else {
            this.collectionTitle = '';
            this.collectionDescription = '';
          }
          this.node.reload();
        },
        error: (error: unknown) => {
          this.busy.set(false);
          this.notifier.error(this.i18n.t('common.create'), error);
        },
      });
  }

  private entry(path: string): Observable<Entry> {
    return this.ldp.load(path).pipe(
      map((view): Entry => {
        const kind = structureKind(view.graph, view.subject);
        const below = path.substring(this.config.communitiesPath().length + 1);
        return {
          path,
          title: view.graph.title(view.subject, this.i18n.locale()),
          description: view.graph.text(view.subject, DCTERMS.description, this.i18n.locale()),
          kind,
          route: kind === 'community' || kind === 'collection' ? ['/', 'communities', ...segmentsOf(below)] : routeTo('browse', path),
        };
      }),
    );
  }
}
