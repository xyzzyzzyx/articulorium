import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject, input, linkedSignal } from '@angular/core';
import { rxResource } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';
import { of } from 'rxjs';

import { ItemView, SearchResponse } from '../../core/api/discovery-api';
import { DiscoveryApiService } from '../../core/api/discovery-api.service';
import { ConfigService } from '../../core/config/config.service';
import { ResponseStatusService } from '../../core/http/response-status.service';
import { LocaleService } from '../../core/i18n/locale.service';
import { SeoService } from '../../core/seo/seo.service';
import { itemStructuredData } from '../../core/seo/structured-data';
import { itemLink, localName } from '../../shared/links';
import { PagerComponent, pageCount, parsePage } from '../../shared/pager.component';
import { ResourceCardComponent } from '../../shared/resource-card.component';
import { MiradorViewerComponent } from '../../viewer/mirador-viewer.component';

/**
 * A resource's page: its trail of communities and collections, title, description, the viewer
 * presenting its IIIF export, its metadata, and the members of communities and collections. The
 * server renders everything but the viewer, with the page's structured data and social sharing
 * metadata; resources that do not exist, are not part of the discovery layer, or are private are
 * answered with 404 alike.
 */
@Component({
  selector: 'disc-item-page',
  imports: [MatButtonModule, MatIconModule, MatProgressBarModule, MiradorViewerComponent, PagerComponent, ResourceCardComponent, RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    @if (item.isLoading() && !item.hasValue()) {
      <mat-progress-bar mode="indeterminate" [attr.aria-label]="'item.loading' | translate" />
    }
    @if (notFound()) {
      <h1>{{ 'errors.notFoundTitle' | translate }}</h1>
      <p class="disc-message">{{ 'errors.notFound' | translate }}</p>
      <p><a [routerLink]="['/', locale(), 'search']">{{ 'home.browseAll' | translate }}</a></p>
    } @else if (item.error()) {
      <h1>{{ 'errors.unavailableTitle' | translate }}</h1>
      <p class="disc-message" role="alert">{{ 'errors.item' | translate }}</p>
    } @else if (resource(); as resource) {
      <nav class="disc-breadcrumb" [attr.aria-label]="'item.breadcrumb' | translate">
        <ol>
          <li><a [routerLink]="['/', locale()]">{{ 'nav.home' | translate }}</a></li>
          @for (crumb of resource.trail; track crumb.path) {
            <li><a [routerLink]="link(crumb.path)">{{ crumb.title }}</a></li>
          }
          <li aria-current="page">{{ resource.title }}</li>
        </ol>
      </nav>

      <article class="disc-item" [class.disc-item--side]="viewerPosition() === 'SIDE' && viewable()">
        <header class="disc-item-header">
          <h1>{{ resource.title }}</h1>
          <p class="disc-item-kind">
            <span class="disc-kind">{{ 'kinds.' + resource.kind.toLowerCase() | translate }}</span>
            @if (resource.date) {
              <span class="disc-muted">{{ date(resource.date) }}</span>
            }
          </p>
        </header>

        @if (viewable() && viewerPosition() !== 'BOTTOM') {
          <section class="disc-item-viewer" aria-labelledby="disc-viewer-heading">
            <h2 id="disc-viewer-heading" class="disc-visually-hidden">{{ 'item.viewer' | translate }}</h2>
            <disc-mirador-viewer [iiif]="resource.iiif!" [title]="resource.title" [thumbnail]="resource.thumbnail" />
          </section>
        } @else if (resource.thumbnail && !viewable()) {
          <img class="disc-item-thumbnail" [src]="resource.thumbnail" [alt]="'viewer.preview' | translate: { title: resource.title }" />
        }

        <div class="disc-item-details">
          @if (resource.description) {
            <p class="disc-item-description">{{ resource.description }}</p>
          }

          @if (fields().length > 0) {
            <section aria-labelledby="disc-details-heading">
              <h2 id="disc-details-heading">{{ 'item.details' | translate }}</h2>
              <dl class="disc-fields">
                @for (field of fields(); track field.field) {
                  <div class="disc-field">
                    <dt>{{ field.label }}</dt>
                    @for (value of field.values; track value.text) {
                      <dd>
                        @if (value.href) {
                          <a [href]="value.href" rel="noopener">{{ value.text }}</a>
                        } @else {
                          {{ value.text }}
                        }
                      </dd>
                    }
                  </div>
                }
              </dl>
            </section>
          }

          <section aria-labelledby="disc-links-heading" class="disc-item-links">
            <h2 id="disc-links-heading" class="disc-visually-hidden">{{ 'item.links' | translate }}</h2>
            @if (resource.iiif) {
              <a mat-stroked-button [href]="resource.iiif.id" type="application/ld+json">
                <mat-icon aria-hidden="true">data_object</mat-icon>
                {{ (resource.iiif.type === 'Collection' ? 'item.iiifCollection' : 'item.iiifManifest') | translate }}
              </a>
            }
            @if (resource.repositoryUrl) {
              <a mat-stroked-button [href]="resource.repositoryUrl">
                <mat-icon aria-hidden="true">open_in_new</mat-icon>
                {{ 'item.repository' | translate }}
              </a>
            }
          </section>
        </div>

        @if (viewable() && viewerPosition() === 'BOTTOM') {
          <section class="disc-item-viewer" aria-labelledby="disc-viewer-heading">
            <h2 id="disc-viewer-heading" class="disc-visually-hidden">{{ 'item.viewer' | translate }}</h2>
            <disc-mirador-viewer [iiif]="resource.iiif!" [title]="resource.title" [thumbnail]="resource.thumbnail" />
          </section>
        }
      </article>

      @if (shownMembers(); as listing) {
        @if (listing.results.length || listing.page > 1) {
          <section id="disc-members" class="disc-section" aria-labelledby="disc-members-heading" [attr.aria-busy]="members.isLoading()">
            <h2 id="disc-members-heading">{{ membersHeading() }}</h2>
            @if (members.isLoading()) {
              <mat-progress-bar mode="indeterminate" [attr.aria-label]="'item.loading' | translate" />
            }
            @if (listing.results.length) {
              <ul class="disc-card-grid">
                @for (member of listing.results; track member.path) {
                  <li><disc-resource-card [resource]="member" [showThumbnail]="thumbnails()" /></li>
                }
              </ul>
            } @else {
              <p class="disc-message">{{ 'item.noMembersOnPage' | translate }}</p>
            }
            <disc-pager [page]="listing.page" [pages]="memberPages()" [label]="'item.membersPagination' | translate" fragment="disc-members" />
          </section>
        }
      }
    }
  `,
  styles: `
    .disc-breadcrumb ol {
      display: flex;
      flex-wrap: wrap;
      gap: 4px;
      margin: 0 0 16px;
      padding: 0;
      list-style: none;
      font: var(--mat-sys-body-medium);
    }
    .disc-breadcrumb li:not(:last-child)::after {
      content: '/';
      margin-inline-start: 4px;
      color: var(--mat-sys-on-surface-variant);
    }
    .disc-item {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: 16px;
    }
    @media (min-width: 1000px) {
      .disc-item--side {
        grid-template-columns: minmax(0, 3fr) minmax(0, 2fr);
        grid-template-areas: 'header header' 'viewer details';
      }
      .disc-item--side .disc-item-header {
        grid-area: header;
      }
      .disc-item--side .disc-item-viewer {
        grid-area: viewer;
      }
      .disc-item--side .disc-item-details {
        grid-area: details;
      }
    }
    .disc-item-kind {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
      margin: 0;
    }
    .disc-item-thumbnail {
      max-width: min(100%, 480px);
      border-radius: var(--mat-sys-corner-medium);
    }
    .disc-item-description {
      max-width: 75ch;
      white-space: pre-line;
    }
    .disc-fields {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: 12px;
      margin: 0;
    }
    @media (min-width: 600px) {
      .disc-field {
        display: grid;
        grid-template-columns: 200px minmax(0, 1fr);
        column-gap: 16px;
      }
      .disc-field dd {
        grid-column: 2;
      }
    }
    .disc-field dt {
      font-weight: 600;
    }
    .disc-field dd {
      margin: 0;
      overflow-wrap: anywhere;
    }
    .disc-item-links {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin-top: 24px;
    }
  `,
})
export class ItemPage {
  private readonly api = inject(DiscoveryApiService);

  private readonly config = inject(ConfigService);

  private readonly locales = inject(LocaleService);

  private readonly seo = inject(SeoService);

  private readonly status = inject(ResponseStatusService);

  /** The resource path (the route's `items/<path>`). */
  readonly path = input.required<string>();

  /** The page of members of a community or collection (the `page` query parameter). */
  readonly page = input(1, { transform: parsePage });

  protected readonly locale = this.locales.locale;

  protected readonly viewerPosition = computed(() => this.config.config().layout.item.viewerPosition);

  protected readonly thumbnails = computed(() => this.config.config().layout.results.thumbnails);

  protected readonly item = rxResource({
    params: () => ({ path: this.path(), lang: this.locale() }),
    stream: ({ params }) => this.api.item(params.path, params.lang),
  });

  protected readonly resource = computed<ItemView | undefined>(() => (this.item.hasValue() ? this.item.value() : undefined));

  protected readonly notFound = computed(() => {
    const error = this.item.error();
    const status = error instanceof HttpErrorResponse ? error.status : (error?.cause as HttpErrorResponse | undefined)?.status;
    return status === 404 || status === 400;
  });

  protected readonly viewable = computed(() => {
    const iiif = this.resource()?.iiif;
    if (!iiif || !this.config.config().viewer.enabled) {
      return false;
    }
    return iiif.type === 'Collection' ? iiif.members > 0 : iiif.images + iiif.videos + iiif.sounds + iiif.documents > 0;
  });

  /** The configured metadata fields that have values, labeled in the current language. */
  protected readonly fields = computed(() => {
    const resource = this.resource();
    if (!resource) {
      return [];
    }
    const labels = new Map(this.config.config().layout.item.fields.map((field) => [field.field, field.label]));
    return resource.fields.map((field) => ({
      field: field.field,
      label: this.locales.text(labels.get(field.field), `fields.${field.field}`),
      values: field.values.map((value) => ({
        text: field.link && /^https?:\/\//.test(value) && field.field === 'type' ? localName(value) : value,
        href: field.link && /^https?:\/\//i.test(value) ? value : undefined,
      })),
    }));
  });

  protected readonly members = rxResource({
    params: () => {
      const resource = this.resource();
      const limit = this.config.config().layout.item.membersLimit;
      return resource && (resource.kind === 'COMMUNITY' || resource.kind === 'COLLECTION') && limit > 0
        ? { within: resource.path, lang: this.locale(), size: limit, page: this.page() }
        : undefined;
    },
    stream: ({ params }) =>
      params
        ? this.api.search({ q: '', page: params.page, size: params.size, facets: {}, filters: [], within: params.within }, params.lang)
        : of(undefined),
  });

  /**
   * The members shown: while another page loads, the current one stays, so the listing (and the
   * position the pager scrolled to) does not collapse.
   */
  protected readonly shownMembers = linkedSignal<{ value?: SearchResponse; loading: boolean }, SearchResponse | undefined>({
    source: () => ({ value: this.members.hasValue() ? this.members.value() : undefined, loading: this.members.isLoading() }),
    computation: (current, previous) => current.value ?? (current.loading ? previous?.value : undefined),
  });

  /** Pages of members; totals are the index's, so a last page may list fewer (or private) members. */
  protected readonly memberPages = computed(() => {
    const members = this.shownMembers();
    return members ? pageCount(members.total, members.size) : 1;
  });

  protected readonly membersHeading = computed(() => {
    this.locale();
    return this.resource()?.kind === 'COMMUNITY' ? this.locales.t('item.communityMembers') : this.locales.t('item.collectionMembers');
  });

  constructor() {
    effect(() => {
      const resource = this.resource();
      if (this.notFound()) {
        this.status.set(404);
        this.seo.update({ title: this.locales.t('errors.notFoundTitle'), path: `items/${this.path()}`, index: false, alternates: false });
        return;
      }
      if (this.item.error()) {
        this.status.set(503);
        return;
      }
      if (!resource) {
        return;
      }
      const siteName = this.locales.text(this.config.config().site.name);
      const url = this.seo.url(`items/${resource.path}`);
      this.seo.update({
        title: resource.title,
        description: resource.description ?? this.locales.text(this.config.config().site.description),
        path: `items/${resource.path}`,
        index: true,
        image: resource.thumbnail,
        imageAlt: resource.title,
        type: resource.kind === 'OBJECT' ? 'article' : 'website',
        structuredData: itemStructuredData(resource, url, (path) => this.seo.url(`items/${path}`), siteName),
      });
    });
  }

  protected link(path: string): string[] {
    return itemLink(this.locale(), path);
  }

  protected date(value: string): string {
    return this.locales.date(value);
  }
}
