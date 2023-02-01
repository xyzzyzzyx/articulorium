/** What a resource is: communities and collections list members, objects open in the viewer. */
export type ResourceKind = 'COMMUNITY' | 'COLLECTION' | 'OBJECT' | 'RESOURCE';

/** A search, as the search page's URL describes it. */
export interface SearchQuery {
  q: string;
  page: number;
  size?: number;
  sort?: string;
  /** Selected facet values by facet identifier. */
  facets: Record<string, string[]>;
  /** Identifiers of the filters switched on. */
  filters: string[];
  within?: string;
  kind?: ResourceKind;
}

export interface SearchResult {
  path: string;
  title: string;
  description?: string;
  kind: ResourceKind;
  types: string[];
  date?: string;
  thumbnail?: string;
}

export interface FacetValue {
  value: string;
  count: number;
  selected: boolean;
}

export interface FacetResult {
  id: string;
  values: FacetValue[];
}

export interface SearchResponse {
  query: string;
  page: number;
  size: number;
  total: number;
  sort?: string;
  results: SearchResult[];
  hidden: number;
  facets: FacetResult[];
  filters: string[];
}

export interface IiifSummary {
  id: string;
  type: 'Manifest' | 'Collection';
  thumbnail?: string;
  canvases: number;
  images: number;
  videos: number;
  sounds: number;
  documents: number;
  members: number;
}

export interface ItemView {
  path: string;
  iri: string;
  title: string;
  description?: string;
  kind: ResourceKind;
  types: string[];
  created?: string;
  modified?: string;
  date?: string;
  languages: string[];
  fields: { field: string; values: string[]; link: boolean }[];
  trail: { path: string; title: string; kind: ResourceKind }[];
  iiif?: IiifSummary;
  thumbnail?: string;
  repositoryUrl?: string;
}

export interface SitemapEntry {
  path: string;
  modified?: string;
}
