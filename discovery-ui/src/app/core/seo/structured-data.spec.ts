import { ItemView } from '../api/discovery-api';
import { itemStructuredData, schemaType, serializeJsonLd, siteStructuredData } from './structured-data';

const item: ItemView = {
  path: 'c/rare/moby',
  iri: 'http://localhost:8000/platform/c/rare/moby',
  title: 'Moby Dick',
  description: 'A whale of a tale',
  kind: 'OBJECT',
  types: ['http://pcdm.org/models#Object', 'http://purl.org/ontology/bibo/Book'],
  date: '1851-10-18T00:00:00Z',
  modified: '2026-02-01T00:00:00Z',
  languages: ['en'],
  fields: [
    { field: 'creator', values: ['Herman Melville'], link: false },
    { field: 'subject', values: ['Whales', 'Sea stories'], link: false },
    { field: 'license', values: ['https://creativecommons.org/publicdomain/mark/1.0/'], link: true },
  ],
  trail: [
    { path: 'c', title: 'Library', kind: 'COMMUNITY' },
    { path: 'c/rare', title: 'Rare books', kind: 'COLLECTION' },
  ],
  iiif: { id: 'http://localhost:8000/transform/iiif/c/rare/moby', type: 'Manifest', canvases: 2, images: 2, videos: 0, sounds: 0, documents: 0, members: 0 },
  thumbnail: 'http://localhost:8000/platform/c/rare/moby/thumb.jpg',
};

const page = (path: string) => `http://localhost:8000/discover/en/items/${path}`;

describe('structured data', () => {
  it('describes resources with schema.org types', () => {
    expect(schemaType('OBJECT', item.types)).toBe('Book');
    expect(schemaType('OBJECT', ['http://pcdm.org/models#Object'])).toBe('CreativeWork');
    expect(schemaType('COLLECTION', [])).toBe('Collection');
    expect(schemaType('COMMUNITY', [])).toBe('Collection');
  });

  it('describes a resource page and its trail', () => {
    const [node, breadcrumbs] = itemStructuredData(item, page(item.path), page, 'Articulorium');

    expect(node).toMatchObject({
      '@type': 'Book',
      '@id': page('c/rare/moby'),
      name: 'Moby Dick',
      description: 'A whale of a tale',
      image: item.thumbnail,
      dateCreated: '1851-10-18T00:00:00Z',
      creator: [{ '@type': 'Person', name: 'Herman Melville' }],
      keywords: 'Whales, Sea stories',
      inLanguage: 'en',
      license: 'https://creativecommons.org/publicdomain/mark/1.0/',
      isPartOf: { '@type': 'Collection', '@id': page('c/rare'), name: 'Rare books' },
      publisher: { '@type': 'Organization', name: 'Articulorium' },
    });
    expect((node['subjectOf'] as Record<string, unknown>)['@id']).toBe(item.iiif!.id);
    expect(breadcrumbs['itemListElement']).toEqual([
      { '@type': 'ListItem', position: 1, name: 'Library', item: page('c') },
      { '@type': 'ListItem', position: 2, name: 'Rare books', item: page('c/rare') },
      { '@type': 'ListItem', position: 3, name: 'Moby Dick', item: page('c/rare/moby') },
    ]);
  });

  it('describes the site and its search', () => {
    const [site] = siteStructuredData('http://x/discover/en/', 'http://x/discover/en/search', 'Library', 'Collections', {
      name: 'Example University',
      logo: 'http://x/logo.svg',
    });
    expect(site['potentialAction']).toMatchObject({ target: { urlTemplate: 'http://x/discover/en/search?q={search_term_string}' } });
    expect(site['publisher']).toEqual({ '@type': 'Organization', name: 'Example University', logo: 'http://x/logo.svg' });
  });

  it('cannot be closed by values', () => {
    const json = serializeJsonLd([{ name: '</script><script>alert(1)</script> & co' }]);
    expect(json).not.toContain('<');
    expect(json).not.toContain('>');
    expect(JSON.parse(json)).toEqual({ name: '</script><script>alert(1)</script> & co' });
  });
});
