import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map } from 'rxjs';

import { ConfigService } from '../config.service';
import { BuildRequest, BuildResult, Topology, TopologySummary } from './topology';

/**
 * Client of the topology service: PCDM topologies and building their resources.
 */
@Injectable({ providedIn: 'root' })
export class TopologyService {
  private readonly http = inject(HttpClient);

  private readonly config = inject(ConfigService);

  private url(...segments: string[]): string {
    return `${this.config.topologyBase()}topologies${segments.map((s) => `/${encodeURIComponent(s)}`).join('')}`;
  }

  /**
   * Lists every topology, built-in ones first.
   *
   * @returns the summaries
   */
  list(): Observable<TopologySummary[]> {
    return this.http.get<TopologySummary[]>(this.url());
  }

  /**
   * Reads a topology.
   *
   * @param id - the identifier
   * @returns the topology
   */
  get(id: string): Observable<Topology> {
    return this.http.get<Topology>(this.url(id));
  }

  /**
   * Creates a custom topology, or replaces it.
   *
   * @param topology - the topology
   * @param replace - whether an existing custom topology is replaced
   * @returns the stored topology
   */
  save(topology: Topology, replace: boolean): Observable<Topology> {
    return replace
      ? this.http.put<Topology>(this.url(topology.id), topology)
      : this.http.post<Topology>(this.url(), topology);
  }

  /**
   * Deletes a custom topology.
   *
   * @param id - the identifier
   * @returns completes when deleted
   */
  delete(id: string): Observable<void> {
    return this.http.delete(this.url(id), { responseType: 'text' }).pipe(map(() => undefined));
  }

  /**
   * Builds the PCDM resources of a topology.
   *
   * @param id - the topology
   * @param request - where and what to build
   * @returns the created resources
   */
  build(id: string, request: BuildRequest): Observable<BuildResult> {
    return this.http.post<BuildResult>(this.url(id, 'instances'), request);
  }

  /**
   * Adds a child to a resource built from a topology.
   *
   * @param id - the topology
   * @param parent - the parent's path
   * @param nodeKey - the child node
   * @param title - the child's title (optional)
   * @returns the created resources
   */
  addMember(id: string, parent: string, nodeKey: string, title?: string): Observable<BuildResult> {
    return this.http.post<BuildResult>(this.url(id, 'members'), { parent, nodeKey, title });
  }
}
