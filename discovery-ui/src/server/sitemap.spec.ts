import { robotsTxt, sitemapXml } from './sitemap';

const settings = {
  publicUrl: 'https://collections.example.org/discover/',
  defaultLocale: 'en',
  locales: ['en', 'de'],
  sitemap: true,
  noindex: false,
};

describe('sitemap', () => {
  it('lists the home page and resource pages with their language versions', () => {
    const xml = sitemapXml(settings, [{ path: 'c/moby & co', modified: '2026-02-01T00:00:00Z' }, { path: 'c/x' }]);

    expect(xml).toContain('<loc>https://collections.example.org/discover/en/</loc>');
    expect(xml).toContain('<loc>https://collections.example.org/discover/en/items/c/moby%20%26%20co</loc>');
    expect(xml).toContain('<lastmod>2026-02-01T00:00:00.000Z</lastmod>');
    expect(xml).toContain('hreflang="de" href="https://collections.example.org/discover/de/items/c/x"');
    expect(xml).toContain('hreflang="x-default" href="https://collections.example.org/discover/en/items/c/x"');
    expect(xml.match(/<url>/g)).toHaveLength(3);
  });
});

describe('robots.txt', () => {
  it('announces the sitemap and keeps crawlers out of the API', () => {
    expect(robotsTxt(settings)).toBe(
      'User-agent: *\nAllow: /discover/\nDisallow: /discover/api/\n\nSitemap: https://collections.example.org/discover/sitemap.xml\n',
    );
  });

  it('keeps everything out of indexes on noindex deployments', () => {
    expect(robotsTxt({ ...settings, noindex: true })).toBe('User-agent: *\nDisallow: /\n');
  });
});
