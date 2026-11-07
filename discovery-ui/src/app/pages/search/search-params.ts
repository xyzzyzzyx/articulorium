import { Params } from '@angular/router';

import { SearchQuery } from '../../core/api/discovery-api';

const first = (value: unknown): string | undefined =>
  Array.isArray(value) ? (value[0] as string | undefined) : typeof value === 'string' ? value : undefined;

const all = (value: unknown): string[] =>
  Array.isArray(value) ? value.filter((v): v is string => typeof v === 'string') : typeof value === 'string' ? [value] : [];

/**
 * Reads a search from the search page's query parameters: `q`, `page`, `size`, `sort`,
 * `f=<facet>:<value>` (repeated) and `filter=<id>` (repeated).
 *
 * @param params - the query parameters
 * @param facetIds - the configured facets (selections of others are dropped)
 * @param filterIds - the configured filters
 */
export function parseSearch(params: Params, facetIds: string[], filterIds: string[]): SearchQuery {
  const page = Number.parseInt(first(params['page']) ?? '1', 10);
  const size = Number.parseInt(first(params['size']) ?? '', 10);
  const facets: Record<string, string[]> = {};
  for (const selection of all(params['f'])) {
    const colon = selection.indexOf(':');
    if (colon <= 0 || colon === selection.length - 1) {
      continue;
    }
    const id = selection.substring(0, colon);
    if (!facetIds.includes(id)) {
      continue;
    }
    const value = selection.substring(colon + 1);
    facets[id] = [...(facets[id] ?? []), value].filter((v, i, list) => list.indexOf(v) === i);
  }
  return {
    q: (first(params['q']) ?? '').trim(),
    page: Number.isFinite(page) && page > 0 ? page : 1,
    size: Number.isFinite(size) && size > 0 ? size : undefined,
    sort: first(params['sort']) || undefined,
    facets,
    filters: all(params['filter']).filter((id, i, list) => filterIds.includes(id) && list.indexOf(id) === i),
  };
}

/**
 * Writes a search as query parameters (the inverse of {@link parseSearch}); defaults are left
 * out, so URLs stay short and stable.
 *
 * @param query - the search
 */
export function searchParams(query: SearchQuery): Params {
  const params: Params = {};
  if (query.q) {
    params['q'] = query.q;
  }
  if (query.page > 1) {
    params['page'] = String(query.page);
  }
  if (query.size) {
    params['size'] = String(query.size);
  }
  if (query.sort) {
    params['sort'] = query.sort;
  }
  const selections = Object.entries(query.facets).flatMap(([id, values]) => values.map((value) => `${id}:${value}`));
  if (selections.length > 0) {
    params['f'] = selections;
  }
  if (query.filters.length > 0) {
    params['filter'] = [...query.filters];
  }
  return params;
}

/**
 * The search with one facet value switched on or off, back on the first page.
 *
 * @param query - the current search
 * @param facet - the facet
 * @param value - the value
 */
export function toggleFacet(query: SearchQuery, facet: string, value: string): SearchQuery {
  const current = query.facets[facet] ?? [];
  const next = current.includes(value) ? current.filter((v) => v !== value) : [...current, value];
  const facets = { ...query.facets };
  if (next.length > 0) {
    facets[facet] = next;
  } else {
    delete facets[facet];
  }
  return { ...query, facets, page: 1 };
}

/**
 * The search with a filter switched on or off, back on the first page.
 *
 * @param query - the current search
 * @param filter - the filter
 */
export function toggleFilter(query: SearchQuery, filter: string): SearchQuery {
  const filters = query.filters.includes(filter) ? query.filters.filter((f) => f !== filter) : [...query.filters, filter];
  return { ...query, filters, page: 1 };
}
