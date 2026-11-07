import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { DiscoveryConfig } from '../config/discovery-config';
import { API_BASE_URL } from './api-base';
import { ItemView, SearchQuery, SearchResponse } from './discovery-api';

/**
 * Client of the discovery API: configuration, searches and resource descriptions, at
 * {@link API_BASE_URL}.
 */
@Injectable({ providedIn: 'root' })
export class DiscoveryApiService {
  private readonly http = inject(HttpClient);

  private readonly base = inject(API_BASE_URL);

  /** The deployment's configuration. */
  config(): Observable<DiscoveryConfig> {
    return this.http.get<DiscoveryConfig>(this.base + 'config');
  }

  /**
   * Searches the discovery layer.
   *
   * @param query - the search
   * @param lang - the visitor's language (titles, language-specific fields)
   */
  search(query: SearchQuery, lang: string): Observable<SearchResponse> {
    let params = new HttpParams().set('page', String(query.page)).set('lang', lang);
    if (query.q) {
      params = params.set('q', query.q);
    }
    if (query.size) {
      params = params.set('size', String(query.size));
    }
    if (query.sort) {
      params = params.set('sort', query.sort);
    }
    for (const [facet, values] of Object.entries(query.facets)) {
      for (const value of values) {
        params = params.append('f', `${facet}:${value}`);
      }
    }
    for (const filter of query.filters) {
      params = params.append('filter', filter);
    }
    if (query.within) {
      params = params.set('within', query.within);
    }
    if (query.kind) {
      params = params.set('kind', query.kind.toLowerCase());
    }
    return this.http.get<SearchResponse>(this.base + 'search', { params });
  }

  /**
   * Describes a resource for its page.
   *
   * @param path - the resource path
   * @param lang - the visitor's language
   */
  item(path: string, lang: string): Observable<ItemView> {
    return this.http.get<ItemView>(this.base + 'item', { params: { path, lang } });
  }
}
