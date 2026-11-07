import { ItemView, ResourceKind } from '../api/discovery-api';

/** A schema.org node of JSON-LD structured data. */
export type JsonLd = Record<string, unknown>;

const SCHEMA = 'https://schema.org';

/** The schema.org type that describes a resource for search engines. */
export function schemaType(kind: ResourceKind, types: string[]): string {
  if (kind === 'COMMUNITY' || kind === 'COLLECTION') {
    return 'Collection';
  }
  const known: Record<string, string> = {
    'http://purl.org/ontology/bibo/Book': 'Book',
    'http://purl.org/ontology/bibo/Article': 'ScholarlyArticle',
    'http://purl.org/ontology/bibo/AcademicArticle': 'ScholarlyArticle',
    'http://purl.org/ontology/bibo/Periodical': 'Periodical',
    'http://purl.org/ontology/bibo/Journal': 'Periodical',
    'http://purl.org/ontology/bibo/Newspaper': 'Newspaper',
    'http://purl.org/ontology/bibo/Issue': 'PublicationIssue',
    'http://purl.org/ontology/bibo/Manuscript': 'Manuscript',
    'http://purl.org/ontology/bibo/Map': 'Map',
    'http://purl.org/ontology/bibo/Image': 'VisualArtwork',
    'http://purl.org/ontology/bibo/AudioVisualDocument': 'MediaObject',
    'http://purl.org/ontology/bibo/Thesis': 'Thesis',
  };
  for (const type of types) {
    if (known[type]) {
      return known[type];
    }
  }
  return 'CreativeWork';
}

/**
 * The structured data of a resource page: the resource (with its preview image, IIIF Manifest,
 * dates, creators and subjects) and its trail of communities and collections.
 *
 * @param item - the resource
 * @param url - its canonical page
 * @param pageUrl - builds the canonical page of another path
 * @param siteName - the name of the site, the publisher
 */
export function itemStructuredData(item: ItemView, url: string, pageUrl: (path: string) => string, siteName: string): JsonLd[] {
  const values = (field: string) => item.fields.find((f) => f.field === field)?.values ?? [];
  const node: JsonLd = {
    '@context': SCHEMA,
    '@type': schemaType(item.kind, item.types),
    '@id': url,
    url,
    name: item.title,
    identifier: item.iri,
    publisher: { '@type': 'Organization', name: siteName },
  };
  if (item.description) {
    node['description'] = item.description;
  }
  if (item.thumbnail) {
    node['image'] = item.thumbnail;
    node['thumbnailUrl'] = item.thumbnail;
  }
  if (item.date) {
    node['dateCreated'] = item.date;
  }
  if (item.modified) {
    node['dateModified'] = item.modified;
  }
  const creators = values('creator');
  if (creators.length > 0) {
    node['creator'] = creators.map((name) => ({ '@type': 'Person', name }));
  }
  const subjects = values('subject');
  if (subjects.length > 0) {
    node['keywords'] = subjects.join(', ');
  }
  if (item.languages.length > 0) {
    node['inLanguage'] = item.languages.length === 1 ? item.languages[0] : item.languages;
  }
  const license = values('license')[0] ?? values('rights').find((value) => /^https?:\/\//.test(value));
  if (license) {
    node['license'] = license;
  }
  if (item.iiif) {
    // IIIF discovery: the Manifest describing how to present the resource
    node['subjectOf'] = { '@type': 'CreativeWork', '@id': item.iiif.id, encodingFormat: 'application/ld+json;profile="http://iiif.io/api/presentation/3/context.json"' };
  }
  const parent = item.trail.at(-1);
  if (parent) {
    node['isPartOf'] = { '@type': 'Collection', '@id': pageUrl(parent.path), name: parent.title };
  }
  const breadcrumbs: JsonLd = {
    '@context': SCHEMA,
    '@type': 'BreadcrumbList',
    itemListElement: [...item.trail.map((crumb) => ({ name: crumb.title, item: pageUrl(crumb.path) })), { name: item.title, item: url }].map(
      (entry, index) => ({ '@type': 'ListItem', position: index + 1, ...entry }),
    ),
  };
  return [node, breadcrumbs];
}

/**
 * The structured data of the home page: the web site and its search.
 *
 * @param url - the home page
 * @param searchUrl - the search page, `{search_term_string}` is added as `q`
 * @param siteName - the site's name
 * @param description - its description
 * @param organization - the publishing organization
 */
export function siteStructuredData(
  url: string,
  searchUrl: string,
  siteName: string,
  description: string,
  organization?: { name?: string; url?: string; logo?: string },
): JsonLd[] {
  const site: JsonLd = {
    '@context': SCHEMA,
    '@type': 'WebSite',
    '@id': url,
    url,
    name: siteName,
    potentialAction: {
      '@type': 'SearchAction',
      target: { '@type': 'EntryPoint', urlTemplate: `${searchUrl}?q={search_term_string}` },
      'query-input': 'required name=search_term_string',
    },
  };
  if (description) {
    site['description'] = description;
  }
  if (organization?.name || organization?.logo) {
    site['publisher'] = {
      '@type': 'Organization',
      name: organization.name ?? siteName,
      ...(organization.url ? { url: organization.url } : {}),
      ...(organization.logo ? { logo: organization.logo } : {}),
    };
  }
  return [site];
}

/**
 * Serializes structured data for a `<script type="application/ld+json">` element: `<` is escaped,
 * so no value can close the element.
 */
export function serializeJsonLd(data: JsonLd[]): string {
  const json = JSON.stringify(data.length === 1 ? data[0] : data);
  return json.replace(/</g, '\\u003c').replace(/>/g, '\\u003e').replace(/&/g, '\\u0026').replace(/\u2028/g, '\\u2028').replace(/\u2029/g, '\\u2029');
}
