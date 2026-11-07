import { isExternal, itemLink, localName, resolveLink } from './links';

describe('links', () => {
  it('resolves links of the settings', () => {
    expect(resolveLink('https://example.org/about', '/discover/', 'de')).toBe('https://example.org/about');
    expect(resolveLink('/ui/', '/discover/', 'de')).toBe('/ui/');
    expect(resolveLink('search?q=maps', '/discover/', 'de')).toBe('/discover/de/search?q=maps');
    expect(isExternal('https://example.org')).toBe(true);
    expect(isExternal('/ui/')).toBe(false);
  });

  it('links resource pages', () => {
    expect(itemLink('en', 'a/b')).toEqual(['/', 'en', 'items', 'a', 'b']);
  });

  it('names IRIs by their last segment', () => {
    expect(localName('http://pcdm.org/models#Object')).toBe('Object');
    expect(localName('http://purl.org/ontology/bibo/Book')).toBe('Book');
    expect(localName('plain')).toBe('plain');
  });
});
