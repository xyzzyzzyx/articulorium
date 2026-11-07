import { parseSearch, searchParams, toggleFacet, toggleFilter } from './search-params';

describe('search parameters', () => {
  const facets = ['type', 'year'];
  const filters = ['digitized'];

  it('reads a search from the URL', () => {
    expect(
      parseSearch(
        { q: '  whale ', page: '3', size: '50', sort: 'title', f: ['type:http://a#b', 'year:1850-1899', 'unknown:x', 'type:http://a#b', 'novalue'], filter: ['digitized', 'other'] },
        facets,
        filters,
      ),
    ).toEqual({ q: 'whale', page: 3, size: 50, sort: 'title', facets: { type: ['http://a#b'], year: ['1850-1899'] }, filters: ['digitized'] });
  });

  it('survives malformed parameters', () => {
    expect(parseSearch({ page: '-2', size: 'x', f: 'type:' }, facets, filters)).toEqual({
      q: '',
      page: 1,
      size: undefined,
      sort: undefined,
      facets: {},
      filters: [],
    });
  });

  it('writes searches back without defaults', () => {
    expect(searchParams({ q: '', page: 1, facets: {}, filters: [] })).toEqual({});
    expect(searchParams({ q: 'whale', page: 2, sort: 'title', facets: { type: ['a', 'b'] }, filters: ['digitized'] })).toEqual({
      q: 'whale',
      page: '2',
      sort: 'title',
      f: ['type:a', 'type:b'],
      filter: ['digitized'],
    });
  });

  it('toggles facet values and filters back to the first page', () => {
    const query = { q: 'x', page: 4, facets: { type: ['a'] }, filters: [] };
    expect(toggleFacet(query, 'type', 'b')).toEqual({ ...query, page: 1, facets: { type: ['a', 'b'] } });
    expect(toggleFacet(query, 'type', 'a')).toEqual({ ...query, page: 1, facets: {} });
    expect(toggleFilter(query, 'digitized')).toEqual({ ...query, page: 1, filters: ['digitized'] });
    expect(toggleFilter({ ...query, filters: ['digitized'] }, 'digitized').filters).toEqual([]);
  });
});
