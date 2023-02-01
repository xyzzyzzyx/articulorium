import { linksWithRel, parseLinks } from './link-header';

describe('parseLinks', () => {
  it('parses repeated and comma separated links with parameters', () => {
    const links = parseLinks([
      '<http://www.w3.org/ns/ldp#Resource>; rel="type", <http://www.w3.org/ns/ldp#BasicContainer>;rel=type',
      '<http://h/p/a/fcr:versions>; rel="timemap"; type="application/link-format"',
      null,
    ]);

    expect(links.length).toBe(3);
    expect(linksWithRel(links, 'type')).toEqual([
      'http://www.w3.org/ns/ldp#Resource',
      'http://www.w3.org/ns/ldp#BasicContainer',
    ]);
    expect(links[2].params['type']).toBe('application/link-format');
  });

  it('splits relation lists', () => {
    const [link] = parseLinks(['<http://h/a>; rel="original timegate"']);

    expect(link.rel).toEqual(['original', 'timegate']);
  });
});
