/** A resource of the sitemap, as the discovery API lists it. */
export interface SitemapResource {
  path: string;
  modified?: string;
}

/** What the sitemap and robots.txt need of the deployment's configuration. */
export interface SitemapSettings {
  publicUrl: string;
  defaultLocale: string;
  locales: string[];
  sitemap: boolean;
  noindex: boolean;
}

const escapeXml = (value: string): string =>
  value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;').replace(/"/g, '&quot;').replace(/'/g, '&apos;');

const encodePath = (path: string): string => path.split('/').map(encodeURIComponent).join('/');

/**
 * Builds the sitemap: the home page and every public resource page, each in the default locale
 * with its alternate language versions (`xhtml:link hreflang`), so the number of URLs stays the
 * number of pages whatever the number of locales.
 *
 * @param settings - the deployment's public address and locales
 * @param resources - the public resources, most recently modified first
 */
export function sitemapXml(settings: SitemapSettings, resources: SitemapResource[]): string {
  const page = (locale: string, path: string) => `${settings.publicUrl}${locale}/${encodePath(path)}`;
  const entry = (path: string, modified?: string) => {
    const alternates = settings.locales
      .map((locale) => `    <xhtml:link rel="alternate" hreflang="${escapeXml(locale)}" href="${escapeXml(page(locale, path))}"/>`)
      .concat(`    <xhtml:link rel="alternate" hreflang="x-default" href="${escapeXml(page(settings.defaultLocale, path))}"/>`);
    const lastmod = modified && !Number.isNaN(Date.parse(modified)) ? `\n    <lastmod>${new Date(modified).toISOString()}</lastmod>` : '';
    return `  <url>\n    <loc>${escapeXml(page(settings.defaultLocale, path))}</loc>${lastmod}\n${alternates.join('\n')}\n  </url>`;
  };
  const urls = [entry(''), ...resources.map((resource) => entry(`items/${resource.path}`, resource.modified))];
  return (
    '<?xml version="1.0" encoding="UTF-8"?>\n' +
    '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9" xmlns:xhtml="http://www.w3.org/1999/xhtml">\n' +
    `${urls.join('\n')}\n</urlset>\n`
  );
}

/**
 * Builds robots.txt: everything may be crawled except the API, and the sitemap is announced; with
 * `noindex` (test and staging deployments) nothing may be crawled.
 *
 * @param settings - the deployment's public address
 */
export function robotsTxt(settings: SitemapSettings): string {
  const base = new URL(settings.publicUrl).pathname;
  if (settings.noindex) {
    return 'User-agent: *\nDisallow: /\n';
  }
  const lines = ['User-agent: *', `Allow: ${base}`, `Disallow: ${base}api/`];
  if (settings.sitemap) {
    lines.push('', `Sitemap: ${settings.publicUrl}sitemap.xml`);
  }
  return `${lines.join('\n')}\n`;
}
