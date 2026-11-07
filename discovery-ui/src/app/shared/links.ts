/**
 * Resolves a link of the settings: absolute and root-relative URLs are kept, anything else is a
 * page of the discovery layer in the current locale (`search` → `/discover/<lang>/search`).
 *
 * @param url - the link
 * @param base - the application's base path (`/discover/`)
 * @param locale - the current locale
 */
export function resolveLink(url: string, base: string, locale: string): string {
  if (/^https?:\/\//i.test(url) || url.startsWith('/')) {
    return url;
  }
  return `${base}${locale}/${url.replace(/^\.\//, '')}`;
}

/** Whether a link leaves the site. */
export function isExternal(url: string): boolean {
  return /^https?:\/\//i.test(url);
}

/**
 * The router commands of a resource page.
 *
 * @param locale - the locale
 * @param path - the resource path
 */
export function itemLink(locale: string, path: string): string[] {
  return ['/', locale, 'items', ...path.split('/')];
}

/**
 * The last segment of an IRI, as a readable fallback label (`http://pcdm.org/models#Object` →
 * `Object`).
 */
export function localName(value: string): string {
  const match = /[#/]([^#/]+)\/?$/.exec(value);
  return match ? decodeURIComponent(match[1]) : value;
}
