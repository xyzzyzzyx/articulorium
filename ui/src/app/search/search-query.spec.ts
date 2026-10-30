import { DEFAULT_PAGE_SIZE, paramsFromQuery, parseCondition, queryFromParams } from './search-query';

describe('search query', () => {
  it('parses conditions with every operator', () => {
    expect(parseCondition('mime_type=image/*')).toEqual({ field: 'mime_type', operator: '=', value: 'image/*' });
    expect(parseCondition('created>=2026-01-01')).toEqual({ field: 'created', operator: '>=', value: '2026-01-01' });
    expect(parseCondition('content_size<10')?.operator).toBe('<');
    expect(parseCondition('colour=red')).toBeUndefined();
    expect(parseCondition('no operator')).toBeUndefined();
  });

  it('round-trips through query parameters', () => {
    const query = queryFromParams({
      condition: ['fedora_id=books/*', 'bogus'],
      max_results: '50',
      offset: '100',
      order_by: 'created',
      order: 'desc',
    });

    expect(query.conditions).toEqual([{ field: 'fedora_id', operator: '=', value: 'books/*' }]);
    expect(query.maxResults).toBe(50);
    expect(query.offset).toBe(100);
    expect(paramsFromQuery(query)).toEqual({
      condition: ['fedora_id=books/*'],
      max_results: 50,
      offset: 100,
      order_by: 'created',
      order: 'desc',
    });
  });

  it('applies defaults and drops blank conditions', () => {
    const query = queryFromParams({ condition: 'rdf_type=*Container', max_results: '-3', order_by: 'colour' });

    expect(query.conditions.length).toBe(1);
    expect(query.maxResults).toBe(DEFAULT_PAGE_SIZE);
    expect(query.offset).toBe(0);
    expect(query.orderBy).toBeUndefined();
    expect(paramsFromQuery({ ...query, conditions: [{ field: 'fedora_id', operator: '=', value: ' ' }] })['condition']).toEqual([]);
  });
});
