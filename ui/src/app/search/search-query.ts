import { Params } from '@angular/router';

import {
  SEARCH_FIELDS,
  SEARCH_OPERATORS,
  SearchCondition,
  SearchField,
  SearchOperator,
  SearchQuery,
} from '../core/ldp/models';

/** Page size used when none is requested. */
export const DEFAULT_PAGE_SIZE = 20;

/**
 * Parses one condition in `fcr:search` syntax, for example `mime_type=image/*` or
 * `created>=2026-01-01`.
 *
 * @param text - the condition
 * @returns the condition, or `undefined` when it is not valid
 */
export function parseCondition(text: string): SearchCondition | undefined {
  const match = /^([a-z_]+)(<=|>=|=|<|>)(.*)$/.exec(text.trim());
  if (!match) {
    return undefined;
  }
  const [, field, operator, value] = match;
  if (!(SEARCH_FIELDS as readonly string[]).includes(field) || !(SEARCH_OPERATORS as readonly string[]).includes(operator)) {
    return undefined;
  }
  return { field: field as SearchField, operator: operator as SearchOperator, value };
}

/**
 * Reads a search from the page's query parameters (the same names `fcr:search` uses, so search
 * pages can be bookmarked and shared).
 *
 * @param params - the query parameters
 * @returns the search
 */
export function queryFromParams(params: Params): SearchQuery {
  const raw: unknown = params['condition'];
  const texts = Array.isArray(raw) ? raw.map(String) : raw === undefined ? [] : [String(raw)];
  const conditions = texts.map(parseCondition).filter((c): c is SearchCondition => c !== undefined);
  const maxResults = positive(params['max_results'], DEFAULT_PAGE_SIZE);
  const offset = positive(params['offset'], 0);
  const orderBy = (SEARCH_FIELDS as readonly string[]).includes(params['order_by']) ? (params['order_by'] as SearchField) : undefined;
  const order = params['order'] === 'desc' ? 'desc' : 'asc';
  return { conditions, maxResults, offset, orderBy, order };
}

/**
 * Writes a search as query parameters.
 *
 * @param query - the search
 * @returns the query parameters
 */
export function paramsFromQuery(query: SearchQuery): Params {
  const params: Params = {
    condition: query.conditions.filter((c) => c.value.trim() !== '').map((c) => `${c.field}${c.operator}${c.value}`),
    max_results: query.maxResults,
    offset: query.offset,
  };
  if (query.orderBy) {
    params['order_by'] = query.orderBy;
    params['order'] = query.order ?? 'asc';
  }
  return params;
}

function positive(value: unknown, fallback: number): number {
  const parsed = Number(value);
  return Number.isInteger(parsed) && parsed >= 0 ? parsed : fallback;
}
