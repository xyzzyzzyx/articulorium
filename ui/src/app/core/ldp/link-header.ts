/** One link of an HTTP `Link` header (RFC 8288). */
export interface Link {
  readonly uri: string;
  readonly rel: readonly string[];
  readonly params: Readonly<Record<string, string>>;
}

/**
 * Parses one or more `Link` header values.
 *
 * @param headers - the header values (comma separated or repeated)
 * @returns the links
 */
export function parseLinks(headers: readonly (string | null | undefined)[]): Link[] {
  const links: Link[] = [];
  for (const header of headers) {
    if (!header) {
      continue;
    }
    const pattern = /<([^>]*)>((?:\s*;\s*[^;,=]+(?:=(?:"[^"]*"|[^;,]*))?)*)/g;
    let match: RegExpExecArray | null;
    while ((match = pattern.exec(header)) !== null) {
      const params: Record<string, string> = {};
      const paramPattern = /;\s*([^;,=\s]+)(?:\s*=\s*(?:"([^"]*)"|([^;,]*)))?/g;
      let param: RegExpExecArray | null;
      while ((param = paramPattern.exec(match[2])) !== null) {
        params[param[1].toLowerCase()] = (param[2] ?? param[3] ?? '').trim();
      }
      links.push({ uri: match[1], rel: (params['rel'] ?? '').split(/\s+/).filter(Boolean), params });
    }
  }
  return links;
}

/**
 * Returns the URIs of the links with a relation.
 *
 * @param links - the links
 * @param rel - the relation, for example `type`
 * @returns the URIs
 */
export function linksWithRel(links: readonly Link[], rel: string): string[] {
  return links.filter((link) => link.rel.includes(rel)).map((link) => link.uri);
}
