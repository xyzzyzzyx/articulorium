import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { ConfigService } from '../config.service';
import { HarvestSource, HarvestSources } from './harvest';

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
}
