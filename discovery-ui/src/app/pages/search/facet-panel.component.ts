import { ChangeDetectionStrategy, Component, computed, inject, input } from '@angular/core';
import { MatIconModule } from '@angular/material/icon';
import { RouterLink } from '@angular/router';
import { TranslatePipe } from '@ngx-translate/core';

import { FacetResult, SearchQuery } from '../../core/api/discovery-api';
import { FacetConfig } from '../../core/config/discovery-config';
import { LocaleService } from '../../core/i18n/locale.service';
import { facetValueLabel } from './facet-labels';
import { searchParams, toggleFacet, toggleFilter } from './search-params';

/**
 * Refines a search: the configured filters and facets with their values and counts. Every value
 * is a link to the refined search, so refining works with the keyboard, without JavaScript, and
 * for crawlers following links.
 */
@Component({
  selector: 'disc-facet-panel',
  imports: [MatIconModule, RouterLink, TranslatePipe],
  changeDetection: ChangeDetectionStrategy.OnPush,
  template: `
    <h2 id="disc-refine">{{ 'search.refine' | translate }}</h2>
    @if (filters().length > 0) {
      <section class="disc-facet" aria-labelledby="disc-filters-heading">
        <h3 id="disc-filters-heading">{{ 'search.filters' | translate }}</h3>
        <ul class="disc-values">
          @for (filter of filters(); track filter.id) {
            <li>
              <a
                [routerLink]="[]"
                [queryParams]="filter.params"
                [class.disc-selected]="filter.selected"
                [attr.aria-current]="filter.selected ? 'true' : null"
              >
                <mat-icon aria-hidden="true">{{ filter.selected ? 'check_box' : 'check_box_outline_blank' }}</mat-icon>
                <span>{{ filter.label }}</span>
                @if (filter.selected) {
                  <span class="disc-visually-hidden">{{ 'search.selected' | translate }}</span>
                }
              </a>
            </li>
          }
        </ul>
      </section>
    }
    @for (facet of facets(); track facet.id) {
      @if (facet.values.length > 0) {
        <details class="disc-facet" [open]="facet.open">
          <summary>
            <h3 [id]="'disc-facet-' + facet.id">{{ facet.label }}</h3>
          </summary>
          <ul class="disc-values" [attr.aria-labelledby]="'disc-facet-' + facet.id">
            @for (value of facet.values; track value.value) {
              <li>
                <a
                  [routerLink]="[]"
                  [queryParams]="value.params"
                  [class.disc-selected]="value.selected"
                  [attr.aria-current]="value.selected ? 'true' : null"
                  rel="nofollow"
                >
                  <mat-icon aria-hidden="true">{{ value.selected ? 'check_box' : 'check_box_outline_blank' }}</mat-icon>
                  <span class="disc-value-label">{{ value.label }}</span>
                  <span class="disc-count">{{ value.count }}</span>
                  @if (value.selected) {
                    <span class="disc-visually-hidden">{{ 'search.selected' | translate }}</span>
                  }
                </a>
              </li>
            }
          </ul>
        </details>
      }
    }
  `,
  styles: `
    :host {
      display: block;
    }
    .disc-facet {
      margin-bottom: 16px;
      padding-bottom: 8px;
      border-bottom: 1px solid var(--mat-sys-outline-variant);
    }
    summary {
      cursor: pointer;
      list-style-position: inside;
    }
    summary h3 {
      display: inline;
    }
    .disc-values {
      margin: 8px 0 0;
      padding: 0;
      list-style: none;
    }
    .disc-values a {
      display: flex;
      align-items: center;
      gap: 6px;
      padding: 4px 2px;
      color: var(--mat-sys-on-surface);
      text-decoration: none;
      border-radius: var(--mat-sys-corner-extra-small);
    }
    .disc-values a:hover {
      background: var(--mat-sys-surface-container-high);
    }
    .disc-values mat-icon {
      flex: none;
      font-size: 20px;
      width: 20px;
      height: 20px;
      color: var(--mat-sys-primary);
    }
    .disc-value-label {
      flex: 1;
      min-width: 0;
      overflow-wrap: anywhere;
    }
    .disc-count {
      color: var(--mat-sys-on-surface-variant);
      font: var(--mat-sys-label-medium);
    }
    .disc-selected {
      font-weight: 600;
    }
  `,
})
export class FacetPanelComponent {
  private readonly locales = inject(LocaleService);

  readonly query = input.required<SearchQuery>();

  readonly config = input.required<FacetConfig[]>();

  readonly results = input.required<FacetResult[]>();

  readonly filterConfig = input.required<{ id: string; label?: { key?: string; value?: string } }[]>();

  protected readonly filters = computed(() => {
    this.locales.locale();
    const query = this.query();
    return this.filterConfig().map((filter) => ({
      id: filter.id,
      label: this.locales.text(filter.label, `filters.${filter.id}`),
      selected: query.filters.includes(filter.id),
      params: searchParams(toggleFilter(query, filter.id)),
    }));
  });

  protected readonly facets = computed(() => {
    this.locales.locale();
    const query = this.query();
    const results = new Map(this.results().map((result) => [result.id, result]));
    return this.config().map((facet) => {
      const values = results.get(facet.id)?.values ?? [];
      const anySelected = values.some((value) => value.selected);
      return {
        id: facet.id,
        label: this.locales.text(facet.label, `facets.${facet.id}`),
        open: facet.expanded || anySelected,
        values: values.map((value) => ({
          value: value.value,
          label: facetValueLabel(facet, value.value, this.locales),
          count: this.locales.number(value.count),
          selected: value.selected,
          params: searchParams(toggleFacet(query, facet.id, value.value)),
        })),
      };
    });
  });
}
