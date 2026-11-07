import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ConfigService } from '../config.service';
import { Classification, ClassificationQueue, HarvestSource, HarvestSources } from './harvest';

/**
 * Client of the harvest service's API (`/harvest/sources` on the gateway): sources, harvests in
 * progress and their reports. The service asks the platform's Web Access Control: viewing needs
 * read and starting or cancelling harvests write access to the container of the harvested records.
 */
@Injectable({ providedIn: 'root' })
export class HarvestService {
  private readonly http = inject(HttpClient);

  private readonly config = inject(ConfigService);

  private url(...segments: string[]): string {
    return `${this.config.harvestBase()}sources${segments.map((s) => `/${encodeURIComponent(s)}`).join('')}`;
  }

  /**
   * Lists the sources, and whether the user may start and cancel harvests.
   *
   * @returns the sources
   */
  list(): Observable<HarvestSources> {
    return this.http.get<HarvestSources>(this.url());
  }

  /**
   * Reads one source.
   *
   * @param id - the source
   * @returns the source
   */
  get(id: string): Observable<HarvestSource> {
    return this.http.get<HarvestSource>(this.url(id));
  }

  /**
   * Starts a harvest in the background.
   *
   * @param id - the source
   * @param mode - `incremental` (what changed since the last complete harvest) or `full`
   * @returns the source, with the harvest running
   */
  start(id: string, mode: 'incremental' | 'full'): Observable<HarvestSource> {
    return this.http.post<HarvestSource>(this.url(id, 'harvests'), { mode });
  }

  /**
   * Asks the running harvest of a source to stop after the record in progress.
   *
   * @param id - the source
   * @returns the source, with the harvest stopping
   */
  cancel(id: string): Observable<HarvestSource> {
    return this.http.delete<HarvestSource>(this.url(id, 'harvests', 'running'));
  }

  /**
   * Reads a page of the review queue: the AI agent's classifications of harvested records.
   *
   * @param options - only one source's, only those waiting for a person (`open`, the default) or
   *   every one (`all`), and the page
   * @returns the page
   */
  classifications(options: {
    readonly source?: string;
    readonly status?: 'open' | 'all';
    readonly offset?: number;
    readonly limit?: number;
  } = {}): Observable<ClassificationQueue> {
    let params = new HttpParams()
      .set('status', options.status ?? 'open')
      .set('offset', String(options.offset ?? 0))
      .set('limit', String(options.limit ?? 25));
    if (options.source) {
      params = params.set('source', options.source);
    }
    return this.http.get<ClassificationQueue>(`${this.config.harvestBase()}classifications`, { params });
  }

  /**
   * Accepts a classification: the record is placed in a collection, with the suggested topology
   * unless another (or `none`) is given.
   *
   * @param source - the source
   * @param record - the record's name
   * @param choice - the collection and topology, when not the suggested ones
   * @returns the accepted classification
   */
  accept(source: string, record: string, choice: { collection?: string; topology?: string } = {}): Observable<Classification> {
    return this.http.post<Classification>(this.review(source, record, 'acceptance'), choice);
  }

  /**
   * Rejects a classification: the record stays where it was harvested.
   *
   * @param source - the source
   * @param record - the record's name
   * @returns the rejected classification
   */
  reject(source: string, record: string): Observable<Classification> {
    return this.http.post<Classification>(this.review(source, record, 'rejection'), null);
  }

  /**
   * Classifies a record again.
   *
   * @param source - the source
   * @param record - the record's name
   * @returns the new classification
   */
  retry(source: string, record: string): Observable<Classification> {
    return this.http.post<Classification>(this.review(source, record, 'retry'), null);
  }

  private review(source: string, record: string, action: string): string {
    return `${this.config.harvestBase()}classifications/${encodeURIComponent(source)}/${encodeURIComponent(record)}/${action}`;
  }
}
