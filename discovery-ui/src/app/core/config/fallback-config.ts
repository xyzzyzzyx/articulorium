import { DiscoveryConfig } from './discovery-config';

/**
 * The configuration used when the discovery service cannot be reached: enough to render a page
 * that says the collections are unavailable, in the default language, with the built-in theme.
 */
export const FALLBACK_CONFIG: DiscoveryConfig = {
  site: { name: { value: 'Articulorium' } },
  theme: { colorScheme: 'light', colors: {}, darkColors: {}, typography: {}, density: 0 },
  layout: {
    header: { search: false, links: [] },
    footer: { links: [] },
    home: { sections: ['HERO'], collectionsPath: 'communities', collectionsLimit: 0, recentLimit: 0 },
    results: {
      view: 'LIST',
      facetPosition: 'START',
      thumbnails: false,
      pageSize: 20,
      pageSizes: [20],
      descriptionLength: 280,
    },
    item: { viewerPosition: 'TOP', fields: [], membersLimit: 0 },
  },
  viewer: {
    enabled: false,
    height: '70vh',
    sideBarOpen: false,
    thumbnailNavigation: 'far-bottom',
    allowFullscreen: true,
    pdf: true,
  },
  search: { facets: [], filters: [], sorts: [] },
  seo: { robots: 'noindex', searchRobots: 'noindex', noindex: true, twitterCard: 'summary', sitemap: false },
  locales: { defaultLocale: 'en', available: [{ tag: 'en', name: 'English', direction: 'ltr' }] },
  urls: { publicUrl: '/discover/', platform: '/platform/', iiif: '/transform/iiif/' },
};
