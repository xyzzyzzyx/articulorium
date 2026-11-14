import { Injectable, inject, signal } from '@angular/core';

import { ResourceKind, SearchResult } from '../../core/api/discovery-api';
import { DiscoveryApiService } from '../../core/api/discovery-api.service';

/** The kinds of resources the tree lists: containers only, objects are on their pages. */
export const TREE_KINDS: ResourceKind[] = ['COMMUNITY', 'COLLECTION'];

/** What the tree knows of one container's communities and collections. */
export interface TreeLevel {
  /** The communities and collections loaded so far, in the configured order. */
  entries: SearchResult[];
  /** How many the index counts (may include private ones the visitor does not see). */
  total: number;
  /** The last page loaded, 0 before the first answer. */
  page: number;
  /** Entries per page. */
  size: number;
  loading: boolean;
  failed: boolean;
}

/** How a level is loaded. */
export interface TreeRequest {
  lang: string;
  /** The container's path. */
  path: string;
  size: number;
  sort?: string;
}

const key = (lang: string, path: string) => `${lang}\n${path}`;

/**
 * The state of the communities and collections tree: the levels loaded (per language, since
 * titles are) and the containers expanded. It lives as long as the application, so the tree is as
 * the visitor left it when they come back from a resource page; levels load a page at a time,
 * each only once.
 */
@Injectable({ providedIn: 'root' })
export class CollectionTreeStore {
  private readonly api = inject(DiscoveryApiService);

  private readonly levels = signal<ReadonlyMap<string, TreeLevel>>(new Map());

  private readonly open = signal<ReadonlySet<string>>(new Set());

  /** The paths of the expanded containers. */
  readonly expandedPaths = this.open.asReadonly();

  /**
   * A container's level, as it loads.
   *
   * @param lang - the visitor's language
   * @param path - the container's path
   */
  level(lang: string, path: string): TreeLevel | undefined {
    return this.levels().get(key(lang, path));
  }

  /**
   * Whether a container is expanded.
   *
   * @param path - the container's path
   */
  expanded(path: string): boolean {
    return this.open().has(path);
  }

  /** Expands or collapses a container. */
  toggle(path: string): void {
    this.open.update((open) => {
      const next = new Set(open);
      if (!next.delete(path)) {
        next.add(path);
      }
      return next;
    });
  }

  /** Collapses every container. */
  collapseAll(): void {
    this.open.set(new Set());
  }

  /**
   * Loads the first page of a level unless it is loaded or loading (a failed level is tried
   * again).
   */
  load(request: TreeRequest): void {
    const level = this.levels().get(key(request.lang, request.path));
    if (level && !level.failed) {
      return;
    }
    if (level?.failed && level.page > 0) {
      // a further page failed: the entries loaded stay
      this.fetch(request, level.page + 1, level);
      return;
    }
    this.fetch(request, 1, undefined);
  }

  /** Loads the next page of a level. */
  more(request: TreeRequest): void {
    const level = this.levels().get(key(request.lang, request.path));
    if (!level || level.loading || !hasMore(level)) {
      return;
    }
    this.fetch(request, level.page + 1, level);
  }

  private fetch(request: TreeRequest, page: number, previous: TreeLevel | undefined): void {
    const id = key(request.lang, request.path);
    this.set(id, {
      entries: previous?.entries ?? [],
      total: previous?.total ?? 0,
      page: previous?.page ?? 0,
      size: previous?.size ?? request.size,
      loading: true,
      failed: false,
    });
    this.api
      .search(
        { q: '', page, size: request.size, sort: request.sort, facets: {}, filters: [], within: request.path, kinds: TREE_KINDS },
        request.lang,
      )
      .subscribe({
        next: (response) => {
          const entries = previous?.entries ?? [];
          // the index may have changed between pages: a container listed twice is listed once
          const known = new Set(entries.map((entry) => entry.path));
          this.set(id, {
            entries: [...entries, ...response.results.filter((result) => !known.has(result.path))],
            total: response.total,
            page: response.page,
            size: response.size,
            loading: false,
            failed: false,
          });
        },
        error: () =>
          this.set(id, {
            entries: previous?.entries ?? [],
            total: previous?.total ?? 0,
            page: previous?.page ?? 0,
            size: previous?.size ?? request.size,
            loading: false,
            failed: true,
          }),
      });
  }

  private set(id: string, level: TreeLevel): void {
    this.levels.update((levels) => new Map(levels).set(id, level));
  }
}

/**
 * The id of the element listing a container's level: stable (the same on the server and in the
 * browser) and unique on the page.
 *
 * @param path - the container's path
 */
export function treeLevelId(path: string): string {
  let hash = 5381;
  for (let i = 0; i < path.length; i++) {
    hash = ((hash * 33) ^ path.charCodeAt(i)) >>> 0;
  }
  return `disc-tree-${hash.toString(36)}-${path.replace(/[^A-Za-z0-9_-]+/g, '_').slice(-48)}`;
}

/** Whether a level has pages left to load. */
export function hasMore(level: TreeLevel): boolean {
  return level.page > 0 && level.page * level.size < level.total;
}
