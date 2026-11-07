import { HttpErrorResponse } from '@angular/common/http';
import { ChangeDetectionStrategy, Component, computed, effect, inject } from '@angular/core';
import { rxResource, toSignal } from '@angular/core/rxjs-interop';
import { MatButtonModule } from '@angular/material/button';
import { MatFormFieldModule } from '@angular/material/form-field';
import { MatIconModule } from '@angular/material/icon';
import { MatProgressBarModule } from '@angular/material/progress-bar';
import { MatSelectModule } from '@angular/material/select';
import { ActivatedRoute, Router, RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { SearchQuery } from '../../core/api/discovery-api';
import { DiscoveryApiService } from '../../core/api/discovery-api.service';
import { ConfigService } from '../../core/config/config.service';
import { ResponseStatusService } from '../../core/http/response-status.service';
import { LocaleService } from '../../core/i18n/locale.service';
import { SeoService } from '../../core/seo/seo.service';
import { SearchBoxComponent } from '../../layout/search-box.component';
import { itemLink } from '../../shared/links';
import { ResourceCardComponent } from '../../shared/resource-card.component';
import { facetValueLabel } from './facet-labels';
import { FacetPanelComponent } from './facet-panel.component';
import { parseSearch, searchParams, toggleFacet, toggleFilter } from './search-params';

/**
 * The search page: search field, results the visitor may read (as a list or a grid), facets and
 * filters, sort orders and pages. The whole search is in the URL, so results can be shared and
 * the back button works; search engines are asked not to index result pages but to follow their
 * links to resource pages.
 */
@Component({
  selector: 'disc-search-page',
  imports: [
    FacetPanelComponent,
    MatButtonModule,
    MatFormFieldModule,
    MatIconModule,
    MatProgressBarModule,
    MatSelectModule,
    ResourceCardComponent,
    RouterLink,
    SearchBoxComponent,
    TranslatePipe,
  ],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h1>{{ heading() }}</h1>
    <disc-search-box [query]="query().q" [large]="true" />

    <div class="disc-search-layout" [class.disc-facets-end]="facetPosition() === 'END'">
      @if (response(); as response) {
        @if (config().search.facets.length > 0 || config().search.filters.length > 0) {
          <aside class="disc-facets" aria-labelledby="disc-refine">
            <disc-facet-panel
              [query]="query()"
              [config]="config().search.facets"
              [results]="response.facets"
              [filterConfig]="config().search.filters"
            />
          </aside>
        }
      }

      <section class="disc-results" aria-labelledby="disc-results-heading">
        @if (search.isLoading()) {
          <mat-progress-bar mode="indeterminate" [attr.aria-label]="'search.loading' | translate" />
        }
        <div class="disc-results-bar">
          <h2 id="disc-results-heading" aria-live="polite">{{ summary() }}</h2>
          @if (sorts().length > 1 && (response()?.total ?? 0) > 0) {
            <mat-form-field appearance="outline" subscriptSizing="dynamic" class="disc-sort">
              <mat-label>{{ 'search.sort' | translate }}</mat-label>
              <mat-select [value]="currentSort()" (selectionChange)="sort($event.value)">
                @for (sort of sorts(); track sort.id) {
                  <mat-option [value]="sort.id">{{ sort.label }}</mat-option>
                }
              </mat-select>
            </mat-form-field>
          }
        </div>

        @if (active().length > 0) {
          <ul class="disc-active" [attr.aria-label]="'search.active' | translate">
            @for (chip of active(); track chip.key) {
              <li>
                <a
                  mat-stroked-button
                  [routerLink]="[]"
                  [queryParams]="chip.params"
                  [attr.aria-label]="'search.remove' | translate: { value: chip.label }"
                >
                  {{ chip.label }}
                  <mat-icon aria-hidden="true" iconPositionEnd>close</mat-icon>
                </a>
              </li>
            }
            <li>
              <a mat-button [routerLink]="[]" [queryParams]="clearParams()">{{ 'search.clear' | translate }}</a>
            </li>
          </ul>
        }

        @if (error(); as error) {
          <p class="disc-message" role="alert">{{ error | translate }}</p>
        } @else if (response(); as response) {
          @if (response.results.length === 0) {
            <div class="disc-message">
              <p>{{ 'search.empty' | translate }}</p>
              @if (active().length > 0) {
                <a [routerLink]="[]" [queryParams]="clearParams()">{{ 'search.clear' | translate }}</a>
              }
            </div>
          } @else if (view() === 'GRID') {
            <ul class="disc-card-grid">
              @for (result of response.results; track result.path) {
                <li><disc-resource-card [resource]="result" [showThumbnail]="thumbnails()" /></li>
              }
            </ul>
          } @else {
            <ol class="disc-result-list" [attr.start]="(response.page - 1) * response.size + 1">
              @for (result of response.results; track result.path) {
                <li class="disc-result">
                  @if (thumbnails() && result.thumbnail) {
                    <img class="disc-result-thumb" [src]="result.thumbnail" alt="" loading="lazy" decoding="async" />
                  }
                  <div class="disc-result-body">
                    <h3>
                      <a [routerLink]="link(result.path)">{{ result.title }}</a>
                    </h3>
                    <p class="disc-result-meta">
                      <span class="disc-kind">{{ 'kinds.' + result.kind.toLowerCase() | translate }}</span>
                      @if (result.date) {
                        <span class="disc-muted">{{ date(result.date) }}</span>
                      }
                    </p>
                    @if (result.description) {
                      <p class="disc-result-description">{{ result.description }}</p>
                    }
                  </div>
                </li>
              }
            </ol>
          }

          @if (pages() > 1) {
            <nav class="disc-pagination" [attr.aria-label]="'search.pagination' | translate">
              @if (response.page > 1) {
                <a mat-stroked-button [routerLink]="[]" [queryParams]="pageParams(response.page - 1)" rel="prev">
                  <mat-icon aria-hidden="true">{{ direction() === 'rtl' ? 'chevron_right' : 'chevron_left' }}</mat-icon>
                  {{ 'search.previous' | translate }}
                </a>
              }
              <span class="disc-page-of">{{ 'search.pageOf' | translate: { page: number(response.page), pages: number(pages()) } }}</span>
              @if (response.page < pages()) {
                <a mat-stroked-button [routerLink]="[]" [queryParams]="pageParams(response.page + 1)" rel="next">
                  {{ 'search.next' | translate }}
                  <mat-icon aria-hidden="true" iconPositionEnd>{{ direction() === 'rtl' ? 'chevron_left' : 'chevron_right' }}</mat-icon>
                </a>
              }
            </nav>
          }
        }
      </section>
    </div>
  `,
  styles: `
    .disc-search-layout {
      display: grid;
      grid-template-columns: minmax(0, 1fr);
      gap: 24px;
      margin-top: 24px;
    }
    /* on narrow screens the results come first, the refinements after them */
    .disc-facets {
      order: 2;
    }
    @media (min-width: 900px) {
      .disc-search-layout {
        grid-template-columns: 280px minmax(0, 1fr);
      }
      .disc-search-layout.disc-facets-end {
        grid-template-columns: minmax(0, 1fr) 280px;
      }
      .disc-facets {
        order: 0;
      }
      .disc-facets-end .disc-facets {
        order: 2;
      }
    }
    .disc-results-bar {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: space-between;
      gap: 12px;
      margin-bottom: 12px;
    }
    .disc-results-bar h2 {
      margin: 0;
    }
    .disc-sort {
      width: 220px;
    }
    .disc-active {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      margin: 0 0 16px;
      padding: 0;
      list-style: none;
    }
    .disc-result-list {
      margin: 0;
      padding: 0;
      list-style: none;
    }
    .disc-result {
      display: flex;
      gap: 16px;
      padding: 16px 0;
      border-bottom: 1px solid var(--mat-sys-outline-variant);
    }
    .disc-result-thumb {
      flex: none;
      width: 96px;
      height: 96px;
      object-fit: cover;
      border-radius: var(--mat-sys-corner-small);
      background: var(--mat-sys-surface-container);
    }
    .disc-result-body {
      min-width: 0;
    }
    .disc-result-meta {
      display: flex;
      flex-wrap: wrap;
      gap: 8px;
      align-items: center;
      margin: 4px 0;
    }
    .disc-result-description {
      margin: 4px 0 0;
      color: var(--mat-sys-on-surface-variant);
      overflow-wrap: anywhere;
    }
    .disc-pagination {
      display: flex;
      flex-wrap: wrap;
      align-items: center;
      justify-content: center;
      gap: 16px;
      margin-top: 24px;
    }
  `,
})
export class SearchPage {
  private readonly api = inject(DiscoveryApiService);

  private readonly configService = inject(ConfigService);

  private readonly locales = inject(LocaleService);

  private readonly seo = inject(SeoService);

  private readonly status = inject(ResponseStatusService);

  private readonly router = inject(Router);

  private readonly params = toSignal(inject(ActivatedRoute).queryParams, { requireSync: true });

  protected readonly config = computed(() => this.configService.config());

  protected readonly direction = this.locales.direction;

  protected readonly facetPosition = computed(() => this.config().layout.results.facetPosition);

  protected readonly view = computed(() => this.config().layout.results.view);

  protected readonly thumbnails = computed(() => this.config().layout.results.thumbnails);

  /** The search the URL describes. */
  protected readonly query = computed<SearchQuery>(() => {
    const search = this.config().search;
    return parseSearch(
      this.params(),
      search.facets.map((facet) => facet.id),
      search.filters.map((filter) => filter.id),
    );
  });

  protected readonly search = rxResource({
    params: () => ({ query: this.query(), lang: this.locales.locale() }),
    stream: ({ params }) => this.api.search(params.query, params.lang),
  });

  protected readonly response = computed(() => (this.search.hasValue() ? this.search.value() : undefined));

  /** The translation key of the error, if the search failed. */
  protected readonly error = computed(() => {
    const error = this.search.error();
    if (!error) {
      return undefined;
    }
    const status = error instanceof HttpErrorResponse ? error.status : (error.cause as HttpErrorResponse | undefined)?.status;
    return status === 400 ? 'errors.badSearch' : 'errors.search';
  });

  protected readonly pages = computed(() => {
    const response = this.response();
    return response ? Math.min(Math.ceil(response.total / response.size), Math.floor(10_000 / response.size)) : 0;
  });

  protected readonly sorts = computed(() => {
    this.locales.locale();
    return this.config().search.sorts.map((sort) => ({ id: sort.id, label: this.locales.text(sort.label, `sorts.${sort.id}`) }));
  });

  protected readonly currentSort = computed(() => this.response()?.sort ?? this.query().sort ?? this.config().search.defaultSort);

  protected readonly heading = computed(() => {
    this.locales.locale();
    const q = this.query().q;
    return q ? this.locales.t('search.titleFor', { query: q }) : this.locales.t('search.browse');
  });

  protected readonly summary = computed(() => {
    this.locales.locale();
    const response = this.response();
    if (!response) {
      return this.locales.t('search.results');
    }
    return this.locales.plural('search.count', response.total);
  });

  /** The selected facet values and filters, each with the search without it. */
  protected readonly active = computed(() => {
    this.locales.locale();
    const query = this.query();
    const chips: { key: string; label: string; params: Record<string, unknown> }[] = [];
    for (const facet of this.config().search.facets) {
      for (const value of query.facets[facet.id] ?? []) {
        chips.push({
          key: `${facet.id}:${value}`,
          label: `${this.locales.text(facet.label, `facets.${facet.id}`)}: ${facetValueLabel(facet, value, this.locales)}`,
          params: searchParams(toggleFacet(query, facet.id, value)),
        });
      }
    }
    for (const filter of this.config().search.filters) {
      if (query.filters.includes(filter.id)) {
        chips.push({
          key: `filter:${filter.id}`,
          label: this.locales.text(filter.label, `filters.${filter.id}`),
          params: searchParams(toggleFilter(query, filter.id)),
        });
      }
    }
    return chips;
  });

  constructor() {
    effect(() => {
      const heading = this.heading();
      this.seo.update({
        title: heading,
        description: this.locales.text(this.config().site.description),
        path: 'search',
        index: false,
      });
    });
    effect(() => {
      const error = this.search.error();
      if (error) {
        const status = error instanceof HttpErrorResponse ? error.status : 503;
        this.status.set(status === 400 ? 400 : 503);
      }
    });
  }

  protected link(path: string): string[] {
    return itemLink(this.locales.locale(), path);
  }

  protected date(value: string): string {
    return this.locales.date(value);
  }

  protected number(value: number): string {
    return this.locales.number(value);
  }

  protected pageParams(page: number): Record<string, unknown> {
    return searchParams({ ...this.query(), page });
  }

  protected clearParams(): Record<string, unknown> {
    const query = this.query();
    return searchParams({ q: query.q, page: 1, size: query.size, sort: query.sort, facets: {}, filters: [] });
  }

  protected sort(sort: string): void {
    void this.router.navigate([], { queryParams: searchParams({ ...this.query(), sort, page: 1 }) });
  }
}
