/**
 * Conversions between resource paths (as used in the user interface's routes, for example
 * `books/moby dick`), platform URLs and resource IRIs.
 *
 * A path is the sequence of decoded segments below the LDP root joined with `/`; the root
 * container's path is the empty string.
 */

/**
 * Splits a path into its non-empty segments.
 *
 * @param path - the path
 * @returns the segments
 */
export function segmentsOf(path: string): string[] {
  return path.split('/').filter((segment) => segment.length > 0);
}

/**
 * Normalises a path: no leading, trailing or repeated slashes.
 *
 * @param path - the path
 * @returns the normalised path
 */
export function normalizePath(path: string | null | undefined): string {
  return segmentsOf(path ?? '').join('/');
}

/**
 * Returns the parent of a path.
 *
 * @param path - the path
 * @returns the parent path, `''` for top level resources, `undefined` for the root
 */
export function parentPath(path: string): string | undefined {
  const segments = segmentsOf(path);
  return segments.length === 0 ? undefined : segments.slice(0, -1).join('/');
}

/**
 * Joins a parent path and a child segment.
 *
 * @param parent - the parent path
 * @param child - the child segment (or relative path)
 * @returns the child path
 */
export function childPath(parent: string, child: string): string {
  return normalizePath(`${parent}/${child}`);
}

/**
 * Builds the platform URL of a path, percent-encoding each segment.
 *
 * @param base - the platform base, ending with `/`
 * @param path - the path
 * @param suffix - extra raw segments such as `fcr:versions` (not encoded)
 * @returns the URL
 */
export function platformUrl(base: string, path: string, ...suffix: string[]): string {
  const encoded = segmentsOf(path).map(encodeURIComponent);
  return base + [...encoded, ...suffix].join('/');
}

/**
 * Converts an IRI minted by the platform back into a path.
 *
 * @param base - the platform base, ending with `/`
 * @param iri - the IRI
 * @returns the path, or `undefined` when the IRI is not below the platform base
 */
export function pathOf(base: string, iri: string): string | undefined {
  const root = base.replace(/\/+$/, '');
  if (iri !== root && !iri.startsWith(`${root}/`)) {
    return undefined;
  }
  const rest = iri.substring(root.length).split(/[?#]/)[0];
  try {
    return segmentsOf(rest).map(decodeURIComponent).join('/');
  } catch {
    return normalizePath(rest);
  }
}

/**
 * Router commands for the views of a path, so Angular encodes each segment.
 *
 * @param view - the view, for example `browse`
 * @param path - the path
 * @returns the route commands
 */
export function routeTo(view: string, path: string): string[] {
  return ['/', view, ...segmentsOf(path)];
}
