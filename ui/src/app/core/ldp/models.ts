import { Graph } from '../rdf/graph';
import { IriTerm } from '../rdf/term';
import { WacAllow } from '../webac/webac';

/** Interaction models a child resource can be created with. */
export type ChildKind = 'basic' | 'direct' | 'indirect' | 'binary';

/** Details of a non-RDF source (binary). */
export interface BinaryInfo {
  /** URL of the bytes. */
  readonly url: string;
  /** URL of the RDF description (the target of SPARQL updates). */
  readonly descriptionUrl: string;
  readonly mimeType?: string;
  readonly size?: number;
  readonly filename?: string;
  /** Message digest, for example `urn:sha-256:…`. */
  readonly digest?: string;
}

/** A resource as shown by the browse view. */
export interface ResourceView {
  /** The resource path (`''` for the root). */
  readonly path: string;
  /** The resource IRI (the subject of its server-managed triples). */
  readonly subject: IriTerm;
  /** URL the resource was read from. */
  readonly url: string;
  /** Its RDF representation (for binaries: the description). */
  readonly graph: Graph;
  /** Binary details for non-RDF sources. */
  readonly binary?: BinaryInfo;
  /** Methods the platform allows on the resource (upper case). */
  readonly allow: ReadonlySet<string>;
  /** LDP interaction model types from the `Link: rel="type"` header. */
  readonly linkTypes: readonly string[];
  /** Entity tag of the representation, used as `If-Match` for updates. */
  readonly etag?: string;
  /** Modes granted by Web Access Control (`WAC-Allow`), when reported. */
  readonly wacAllow?: WacAllow;
  /** Whether the resource is an archival group. */
  readonly archivalGroup?: boolean;
  /** IRI of the archival group a member belongs to. */
  readonly archivalGroupIri?: string;
  /** IRI of the resource's notification inbox (containers). */
  readonly inbox?: string;
}

/** Request to create a child resource. */
export interface ChildRequest {
  readonly kind: ChildKind;
  /** Whether a container is created as an archival group. */
  readonly archivalGroup?: boolean;
  /** A name suggested to the platform (POST with `Slug`); it picks another when taken. */
  readonly slugHint?: string;
  /** Identifier of the child; when empty the platform mints one. */
  readonly slug?: string;
  /** Turtle body for RDF sources. */
  readonly turtle?: string;
  /** Bytes of a binary. */
  readonly file?: Blob;
  /** File name of a binary. */
  readonly filename?: string;
  /** Media type of a binary; defaults to the file's type. */
  readonly mimeType?: string;
}

/** One entry of a version list (`fcr:versions`). */
export interface VersionEntry {
  readonly id: string;
  readonly uri: string;
  readonly created?: string;
  readonly agent?: string;
  readonly message?: string;
  readonly hasContent: boolean;
}

/** The version list of a resource. */
export interface VersionList {
  readonly original: string;
  readonly timemap: string;
  readonly versions: readonly VersionEntry[];
}

/** A historic version of a resource. */
export interface MementoView {
  readonly path: string;
  readonly versionId: string;
  readonly subject: IriTerm;
  readonly graph: Graph;
  /** The `Memento-Datetime` of the version. */
  readonly datetime?: string;
  /** URL of the version's bytes when the resource is a binary. */
  readonly contentUrl?: string;
}

/** Outcome of a fixity check (`fcr:fixity`). */
export interface FixityReport {
  readonly resource: string;
  readonly status: 'SUCCESS' | 'FAILURE';
  readonly storageVersion?: string;
  readonly expectedSize?: number;
  readonly actualSize: number;
  readonly expectedSha256?: string;
  readonly actualSha256?: string;
  readonly storageValid: boolean;
  readonly storageErrors: readonly string[];
  readonly checked: string;
}

/** Search fields of `fcr:search`. */
export const SEARCH_FIELDS = ['fedora_id', 'created', 'modified', 'content_size', 'mime_type', 'rdf_type'] as const;

/** A search field. */
export type SearchField = (typeof SEARCH_FIELDS)[number];

/** Comparison operators of `fcr:search`. */
export const SEARCH_OPERATORS = ['=', '<', '<=', '>', '>='] as const;

/** A comparison operator. */
export type SearchOperator = (typeof SEARCH_OPERATORS)[number];

/** One search condition. */
export interface SearchCondition {
  readonly field: SearchField;
  readonly operator: SearchOperator;
  readonly value: string;
}

/** A search request. */
export interface SearchQuery {
  readonly conditions: readonly SearchCondition[];
  readonly maxResults: number;
  readonly offset: number;
  readonly orderBy?: SearchField;
  readonly order?: 'asc' | 'desc';
}

/** One search hit. */
export interface SearchItem {
  readonly fedora_id: string;
  readonly created?: string;
  readonly modified?: string;
  readonly content_size?: number | null;
  readonly mime_type?: string | null;
  readonly rdf_type?: readonly string[];
}

/** A page of search results. */
export interface SearchResult {
  readonly items: readonly SearchItem[];
  readonly pagination: {
    readonly maxResults: number;
    readonly offset: number;
    readonly totalResults: number;
    readonly pageNumber: number;
    readonly numberOfPages: number;
  };
}

/** Media type detection result of the service's `api/media-type`. */
export interface MediaTypeDetection {
  readonly filename: string;
  readonly mediaType: string;
  readonly size: number;
}
