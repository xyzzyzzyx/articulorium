import { HttpClient, HttpHeaders, HttpParams, HttpResponse } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable, map, of, switchMap } from 'rxjs';

import { ConfigService } from '../config.service';
import { Graph, lastSegment } from '../rdf/graph';
import { parseNTriples } from '../rdf/ntriples';
import { IriTerm } from '../rdf/term';
import { ARCHIVAL_GROUP, BINARY, LDP, LDP_INBOX } from '../rdf/vocabulary';
import { parseWacAllow } from '../webac/webac';
import { linksWithRel, parseLinks } from './link-header';
import {
  BinaryInfo,
  ChildKind,
  ChildRequest,
  FixityReport,
  MediaTypeDetection,
  MementoView,
  ResourceView,
  SearchQuery,
  SearchResult,
  VersionList,
} from './models';
import { childPath, normalizePath, platformUrl } from './paths';

/** The RDF syntax the user interface reads: no prefixes or contexts to process. */
export const N_TRIPLES = 'application/n-triples';

/** Media type of SPARQL Update requests. */
export const SPARQL_UPDATE = 'application/sparql-update';

/** LDP interaction model type of each kind of child. */
const KIND_TYPES: Record<ChildKind, string> = {
  basic: LDP.basicContainer,
  direct: LDP.directContainer,
  indirect: LDP.indirectContainer,
  binary: LDP.nonRdfSource,
};

/**
 * Client of the Linked Data Platform and its Fedora API extensions (`fcr:versions`,
 * `fcr:fixity`, `fcr:search`). Every method returns a cold observable.
 */
@Injectable({ providedIn: 'root' })
export class LdpService {
  private readonly http = inject(HttpClient);

  private readonly config = inject(ConfigService);

  /**
   * The platform base URL, ending with `/`.
   *
   * @returns the base
   */
  base(): string {
    return this.config.platformBase();
  }

  /**
   * The URL of a path, with optional extension segments.
   *
   * @param path - the resource path
   * @param suffix - raw segments to append (for example `fcr:versions`)
   * @returns the URL
   */
  url(path: string, ...suffix: string[]): string {
    return platformUrl(this.base(), path, ...suffix);
  }

  /**
   * Reads a resource. Binaries are recognised by their type; the platform answers an RDF request
   * for a binary with its description (after a redirect), which is what is shown.
   *
   * @param path - the resource path
   * @returns the resource
   */
  load(path: string): Observable<ResourceView> {
    const normalized = normalizePath(path);
    const url = this.url(normalized);
    return this.http
      .get(url, { headers: { Accept: N_TRIPLES }, observe: 'response', responseType: 'text' })
      .pipe(
        switchMap((response) => {
          const graph = new Graph(parseNTriples(response.body ?? ''));
          const subject = findSubject(graph, url);
          const view: ResourceView = {
            path: normalized,
            subject,
            url,
            graph,
            allow: allowOf(response),
            linkTypes: linksWithRel(parseLinks(response.headers.getAll('Link') ?? []), 'type'),
            etag: response.headers.get('ETag') ?? undefined,
            ...headerFacts(response),
          };
          if (!graph.hasType(subject, LDP.nonRdfSource)) {
            return of(view);
          }
          const descriptionUrl = response.url && response.url !== url ? response.url : `${url}/description`;
          return this.http.head(url, { observe: 'response' }).pipe(
            map((head) => ({
              ...view,
              allow: allowOf(head),
              linkTypes: linksWithRel(parseLinks(head.headers.getAll('Link') ?? []), 'type'),
              ...headerFacts(head),
              binary: binaryInfo(graph, subject, url, descriptionUrl),
            })),
          );
        }),
      );
  }

  /**
   * Creates a child resource. With an identifier the child is created by `PUT` (refusing to
   * overwrite an existing resource), otherwise by `POST` and the platform mints the identifier.
   *
   * @param parent - the parent container's path
   * @param request - what to create
   * @returns the URL of the new resource
   */
  createChild(parent: string, request: ChildRequest): Observable<string> {
    let headers = new HttpHeaders().set('Link', `<${KIND_TYPES[request.kind]}>; rel="type"`);
    if (request.archivalGroup && request.kind !== 'binary') {
      headers = headers.append('Link', `<${ARCHIVAL_GROUP.type}>; rel="type"`);
    }
    let body: Blob | string | null;
    if (request.kind === 'binary') {
      if (!request.file) {
        throw new Error('A binary needs a file');
      }
      body = request.file;
      headers = headers.set('Content-Type', request.mimeType || request.file.type || 'application/octet-stream');
      if (request.filename) {
        headers = headers.set('Content-Disposition', contentDisposition(request.filename));
      }
    } else {
      body = request.turtle?.trim() ? request.turtle : null;
      if (body) {
        headers = headers.set('Content-Type', 'text/turtle');
      }
    }
    const slug = request.slug?.trim();
    const target = slug ? this.url(childPath(parent, slug)) : this.url(parent);
    const response$ = slug
      ? this.http.put(target, body, { headers: headers.set('If-None-Match', '*'), observe: 'response', responseType: 'text' })
      : this.http.post(target, body, {
          headers: request.slugHint || request.filename ? headers.set('Slug', request.slugHint || request.filename || '') : headers,
          observe: 'response',
          responseType: 'text',
        });
    return response$.pipe(map((response) => response.headers.get('Location') ?? target));
  }

  /**
   * Replaces the bytes of a binary.
   *
   * @param path - the binary's path
   * @param file - the new bytes
   * @param mimeType - their media type; defaults to the file's type
   * @param filename - the file name to record
   * @returns completes when stored
   */
  replaceBinary(path: string, file: Blob, mimeType?: string, filename?: string): Observable<void> {
    let headers = new HttpHeaders().set('Content-Type', mimeType || file.type || 'application/octet-stream');
    if (filename) {
      headers = headers.set('Content-Disposition', contentDisposition(filename));
    }
    return this.http.put(this.url(path), file, { headers, responseType: 'text' }).pipe(map(() => undefined));
  }

  /**
   * Applies a SPARQL Update to an RDF source (for binaries: to their description).
   *
   * @param url - the RDF source URL
   * @param update - the SPARQL Update request
   * @param etag - the entity tag the update is based on, sent as `If-Match`
   * @returns completes when applied
   */
  patch(url: string, update: string, etag?: string): Observable<void> {
    let headers = new HttpHeaders().set('Content-Type', SPARQL_UPDATE);
    if (etag) {
      headers = headers.set('If-Match', etag);
    }
    return this.http.patch(url, update, { headers, responseType: 'text' }).pipe(map(() => undefined));
  }

  /**
   * Deletes a resource (containers recursively).
   *
   * @param path - the resource path
   * @returns completes when deleted
   */
  delete(path: string): Observable<void> {
    return this.http.delete(this.url(path), { responseType: 'text' }).pipe(map(() => undefined));
  }

  /**
   * Lists the versions of a resource.
   *
   * @param path - the resource path
   * @returns the version list, oldest first
   */
  versions(path: string): Observable<VersionList> {
    return this.http.get<VersionList>(this.url(path, 'fcr:versions'), { headers: { Accept: 'application/json' } });
  }

  /**
   * Records the current state of a resource as a version.
   *
   * @param path - the resource path
   * @returns the URL of the new version
   */
  createVersion(path: string): Observable<string> {
    return this.http
      .post(this.url(path, 'fcr:versions'), null, { observe: 'response', responseType: 'text' })
      .pipe(map((response) => response.headers.get('Location') ?? response.body ?? ''));
  }

  /**
   * Reads a historic version.
   *
   * @param path - the resource path
   * @param versionId - the version, for example `v2`
   * @returns the memento
   */
  memento(path: string, versionId: string): Observable<MementoView> {
    const normalized = normalizePath(path);
    const url = this.url(normalized, 'fcr:versions', encodeURIComponent(versionId));
    return this.http
      .get(url, { headers: { Accept: N_TRIPLES }, observe: 'response', responseType: 'text' })
      .pipe(
        map((response) => {
          const graph = new Graph(parseNTriples(response.body ?? ''));
          const subject = findSubject(graph, this.url(normalized));
          return {
            path: normalized,
            versionId,
            subject,
            graph,
            datetime: response.headers.get('Memento-Datetime') ?? undefined,
            contentUrl: graph.hasType(subject, LDP.nonRdfSource) ? url : undefined,
          };
        }),
      );
  }

  /**
   * Checks the fixity of a binary.
   *
   * @param path - the binary's path
   * @returns the report
   */
  fixity(path: string): Observable<FixityReport> {
    return this.http.get<FixityReport>(this.url(path, 'fcr:fixity'));
  }

  /**
   * Searches the repository.
   *
   * @param query - conditions, paging and ordering
   * @returns a page of results
   */
  search(query: SearchQuery): Observable<SearchResult> {
    let params = new HttpParams().set('max_results', query.maxResults).set('offset', query.offset);
    for (const condition of query.conditions) {
      params = params.append('condition', `${condition.field}${condition.operator}${condition.value}`);
    }
    if (query.orderBy) {
      params = params.set('order_by', query.orderBy).set('order', query.order ?? 'asc');
    }
    return this.http.get<SearchResult>(`${this.base()}fcr:search`, { params });
  }

  /**
   * Detects the media type of a file from its content (Apache Tika in the service module).
   *
   * @param file - the file
   * @returns the detection result
   */
  detectMediaType(file: File): Observable<MediaTypeDetection> {
    const form = new FormData();
    form.append('file', file, file.name);
    return this.http.post<MediaTypeDetection>('api/media-type', form);
  }
}

/**
 * Finds the subject describing the resource at a URL: the URL itself, or the variant with or
 * without a trailing slash (the root container).
 *
 * @param graph - the representation
 * @param url - the resource URL
 * @returns the subject
 */
export function findSubject(graph: Graph, url: string): IriTerm {
  const candidates = [url, url.endsWith('/') ? url.slice(0, -1) : `${url}/`];
  for (const value of candidates) {
    if (graph.about({ kind: 'iri', value }).length > 0) {
      return { kind: 'iri', value };
    }
  }
  return { kind: 'iri', value: url };
}

/**
 * Reads the methods of an `Allow` header.
 *
 * @param response - the response
 * @returns the upper-case methods
 */
/**
 * Reads what the platform says about a resource in its headers: the modes Web Access Control
 * grants, archival group membership and the notification inbox.
 *
 * @param response - a GET or HEAD response
 * @returns the facts
 */
export function headerFacts(
  response: HttpResponse<unknown>,
): Pick<ResourceView, 'wacAllow' | 'archivalGroup' | 'archivalGroupIri' | 'inbox'> {
  const links = parseLinks(response.headers.getAll('Link') ?? []);
  return {
    wacAllow: parseWacAllow(response.headers.get('WAC-Allow')),
    archivalGroup: linksWithRel(links, 'type').includes(ARCHIVAL_GROUP.type),
    archivalGroupIri: linksWithRel(links, ARCHIVAL_GROUP.rel)[0],
    inbox: linksWithRel(links, LDP_INBOX)[0],
  };
}

export function allowOf(response: HttpResponse<unknown>): Set<string> {
  return new Set(
    (response.headers.get('Allow') ?? '')
      .split(',')
      .map((method) => method.trim().toUpperCase())
      .filter(Boolean),
  );
}

/**
 * The name a binary is saved under when downloaded: its recorded file name or, when none was
 * recorded (it was created without `Content-Disposition`), the last segment of its path.
 *
 * @param binary - the binary's details
 * @returns the file name, never empty for a binary below the root
 */
export function downloadName(binary: Pick<BinaryInfo, 'url' | 'filename'>): string {
  return binary.filename || lastSegment(binary.url);
}

/**
 * Collects the binary details from a description.
 *
 * @param graph - the description
 * @param subject - the binary
 * @param url - the binary's URL
 * @param descriptionUrl - the description's URL
 * @returns the details
 */
export function binaryInfo(graph: Graph, subject: IriTerm, url: string, descriptionUrl: string): BinaryInfo {
  const size = graph.value(subject, BINARY.size);
  return {
    url,
    descriptionUrl,
    mimeType: graph.value(subject, BINARY.mimeType),
    size: size === undefined ? undefined : Number(size),
    filename: graph.value(subject, BINARY.filename),
    digest: graph.value(subject, BINARY.digest),
  };
}

/**
 * Builds an RFC 6266 `Content-Disposition` value carrying a (possibly non-ASCII) file name.
 *
 * @param filename - the file name
 * @returns the header value
 */
export function contentDisposition(filename: string): string {
  const ascii = filename.replace(/[^\x20-\x7e]|["\\]/g, '_');
  return `attachment; filename="${ascii}"; filename*=UTF-8''${encodeURIComponent(filename)}`;
}
