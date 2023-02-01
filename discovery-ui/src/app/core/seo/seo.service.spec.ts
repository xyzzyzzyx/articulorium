import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';
import { Title } from '@angular/platform-browser';

import { testConfig } from '../../../testing/config';
import { setUpDiscovery, testProviders } from '../../../testing/providers';
import { SeoService } from './seo.service';

describe('SeoService', () => {
  let document: Document;

  const meta = (selector: string) => document.head.querySelector(`meta[${selector}]`)?.getAttribute('content');

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: testProviders() });
    document = TestBed.inject(DOCUMENT);
  });

  it('describes indexable pages with their language versions', async () => {
    await setUpDiscovery('de');
    TestBed.inject(SeoService).update({
      title: 'Moby Dick',
      description: 'A whale\n of a tale',
      path: 'items/c/moby dick',
      index: true,
      image: 'http://x/thumb.jpg',
      structuredData: [{ '@type': 'Book', name: '</script>' }],
    });

    expect(TestBed.inject(Title).getTitle()).toBe('Moby Dick | Articulorium');
    expect(meta('name="description"')).toBe('A whale of a tale');
    expect(meta('name="robots"')).toBe('index, follow');
    expect(meta('property="og:url"')).toBe('http://localhost:8000/discover/de/items/c/moby%20dick');
    expect(meta('property="og:locale"')).toBe('de');
    expect(meta('property="og:image"')).toBe('http://x/thumb.jpg');
    expect(meta('name="twitter:card"')).toBe('summary_large_image');
    expect(meta('name="twitter:site"')).toBe('@articulorium');
    expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe(
      'http://localhost:8000/discover/de/items/c/moby%20dick',
    );
    const alternates = Array.from(document.head.querySelectorAll('link[rel="alternate"]')).map((link) => link.getAttribute('hreflang'));
    expect(alternates).toEqual(['en', 'ar', 'de', 'pt-BR', 'x-default']);
    const jsonLd = document.getElementById('disc-structured-data');
    expect(jsonLd?.getAttribute('type')).toBe('application/ld+json');
    expect(jsonLd?.textContent).not.toContain('<');
  });

  it('keeps search pages and noindex deployments out of indexes', async () => {
    await setUpDiscovery('en');
    const seo = TestBed.inject(SeoService);
    seo.update({ title: 'Results', path: 'search', index: false });
    expect(meta('name="robots"')).toBe('noindex, follow');
    expect(meta('name="twitter:card"')).toBe('summary');

    const config = testConfig();
    config.seo.noindex = true;
    await setUpDiscovery('en', config);
    seo.update({ title: '', path: '', index: true });
    expect(meta('name="robots"')).toBe('noindex, nofollow');
    expect(document.head.querySelectorAll('link[rel="alternate"]')).toHaveLength(0);
    expect(document.head.querySelectorAll('link[rel="canonical"]')).toHaveLength(1);
    expect(document.getElementById('disc-structured-data')).toBeNull();
  });
});
