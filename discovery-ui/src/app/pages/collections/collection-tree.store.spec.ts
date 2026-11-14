import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { testProviders } from '../../../testing/providers';
import { SearchResponse, SearchResult } from '../../core/api/discovery-api';
import { CollectionTreeStore, hasMore, treeLevelId } from './collection-tree.store';

const entry = (path: string, kind: SearchResult['kind'] = 'COMMUNITY'): SearchResult => ({ path, title: path, kind, types: [] });

const page = (number: number, total: number, results: SearchResult[]): SearchResponse => ({
  query: '',
  page: number,
  size: 2,
  total,
  results,
  hidden: 0,
  facets: [],
  filters: [],
});

describe('CollectionTreeStore', () => {
  let store: CollectionTreeStore;
  let http: HttpTestingController;
  const request = { lang: 'en', path: 'communities', size: 2, sort: 'title' };

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: testProviders() });
    store = TestBed.inject(CollectionTreeStore);
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('loads the communities and collections of a container once, a page at a time', () => {
    store.load(request);
    store.load(request);
    const first = http.expectOne((r) => r.url === 'http://test/discover/api/search');
    expect(first.request.params.get('within')).toBe('communities');
    expect(first.request.params.getAll('kind')).toEqual(['community', 'collection']);
    expect(first.request.params.get('sort')).toBe('title');
    expect(first.request.params.get('size')).toBe('2');
    expect(first.request.params.get('lang')).toBe('en');
    expect(store.level('en', 'communities')?.loading).toBe(true);
    first.flush(page(1, 3, [entry('communities/a'), entry('communities/b', 'COLLECTION')]));

    const level = store.level('en', 'communities')!;
    expect(level.entries.map((e) => e.path)).toEqual(['communities/a', 'communities/b']);
    expect(hasMore(level)).toBe(true);

    store.more(request);
    store.more(request);
    const second = http.expectOne((r) => r.url === 'http://test/discover/api/search');
    expect(second.request.params.get('page')).toBe('2');
    // a container that moved between pages is listed once
    second.flush(page(2, 3, [entry('communities/b', 'COLLECTION'), entry('communities/c')]));

    const more = store.level('en', 'communities')!;
    expect(more.entries.map((e) => e.path)).toEqual(['communities/a', 'communities/b', 'communities/c']);
    expect(hasMore(more)).toBe(false);
    store.more(request);
    // titles are per language: another language is another level
    expect(store.level('de', 'communities')).toBeUndefined();
  });

  it('keeps what it loaded when a page fails, and tries again', () => {
    store.load(request);
    http.expectOne(() => true).flush(page(1, 4, [entry('communities/a'), entry('communities/b')]));
    store.more(request);
    http.expectOne(() => true).flush({ status: 503 }, { status: 503, statusText: 'Unavailable' });

    const failed = store.level('en', 'communities')!;
    expect(failed.failed).toBe(true);
    expect(failed.entries).toHaveLength(2);

    store.load(request);
    const retry = http.expectOne(() => true);
    expect(retry.request.params.get('page')).toBe('2');
    retry.flush(page(2, 4, [entry('communities/c')]));
    expect(store.level('en', 'communities')!.entries).toHaveLength(3);
  });

  it('expands and collapses containers', () => {
    store.toggle('communities/a');
    store.toggle('communities/b');
    expect(store.expanded('communities/a')).toBe(true);
    store.toggle('communities/a');
    expect(store.expanded('communities/a')).toBe(false);
    expect([...store.expandedPaths()]).toEqual(['communities/b']);
    store.collapseAll();
    expect(store.expandedPaths().size).toBe(0);
  });
});

describe('treeLevelId', () => {
  it('is stable, a valid id and different for paths that read alike', () => {
    expect(treeLevelId('communities/a b')).toBe(treeLevelId('communities/a b'));
    expect(treeLevelId('communities/a b')).toMatch(/^disc-tree-[a-z0-9]+-[A-Za-z0-9_-]+$/);
    expect(treeLevelId('communities/a_b')).not.toBe(treeLevelId('communities/a/b'));
  });
});
