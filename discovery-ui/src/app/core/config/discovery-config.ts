/**
 * The deployment's configuration, as the discovery service serves it (`GET api/config`): identity,
 * theme, layouts, viewer, search options, search engine directives, locales and public addresses.
 * Everything here is set in the discovery service's settings (`articulorium.discovery.*`), so a
 * deployment is customized without changing these components.
 */
export interface DiscoveryConfig {
  site: SiteConfig;
  theme: ThemeConfig;
  layout: LayoutConfig;
  viewer: ViewerConfig;
  search: SearchConfig;
  seo: SeoConfig;
  locales: LocalesConfig;
  urls: UrlsConfig;
}

/**
 * Text in several languages: a translation key of the interface, translations by language tag,
 * and a value used when nothing else matches.
 */
export interface LocalizedText {
  key?: string;
  value?: string;
  translations?: Record<string, string>;
}

export interface SiteConfig {
  name: LocalizedText;
  description?: LocalizedText;
  tagline?: LocalizedText;
  logoUrl?: string;
  faviconUrl?: string;
  defaultImageUrl?: string;
  organizationName?: string;
  organizationUrl?: string;
  twitterSite?: string;
}

export interface ThemeConfig {
  colorScheme: 'light' | 'dark' | 'system';
  colors: Record<string, string>;
  darkColors: Record<string, string>;
  typography: {
    brandFamily?: string;
    plainFamily?: string;
    stylesheetUrl?: string;
    baseSize?: string;
  };
  cornerRadius?: number;
  density: number;
  contentWidth?: string;
  stylesheetUrl?: string;
  customCss?: string;
}

export interface NavigationLink {
  label: LocalizedText;
  url: string;
}

export type HomeSection = 'HERO' | 'SEARCH' | 'COLLECTIONS' | 'RECENT';

export interface LayoutConfig {
  header: { search: boolean; links: NavigationLink[] };
  footer: { text?: LocalizedText; links: NavigationLink[] };
  home: {
    sections: HomeSection[];
    collectionsPath: string;
    collectionsLimit: number;
    recentLimit: number;
  };
  results: {
    view: 'LIST' | 'GRID';
    facetPosition: 'START' | 'END';
    thumbnails: boolean;
    pageSize: number;
    pageSizes: number[];
    descriptionLength: number;
  };
  item: {
    viewerPosition: 'TOP' | 'BOTTOM' | 'SIDE';
    fields: { field: string; label?: LocalizedText; link: boolean }[];
    membersLimit: number;
  };
}

export interface ViewerConfig {
  enabled: boolean;
  height: string;
  sideBarOpen: boolean;
  thumbnailNavigation: 'far-bottom' | 'far-right' | 'off';
  allowFullscreen: boolean;
  pdf: boolean;
}

export interface FacetConfig {
  id: string;
  label?: LocalizedText;
  type: 'TERMS' | 'YEAR_RANGE';
  limit: number;
  expanded: boolean;
  values: Record<string, LocalizedText>;
}

export interface ChoiceConfig {
  id: string;
  label?: LocalizedText;
}

export interface SearchConfig {
  facets: FacetConfig[];
  filters: ChoiceConfig[];
  sorts: ChoiceConfig[];
  defaultSort?: string;
}

export interface SeoConfig {
  robots: string;
  searchRobots: string;
  noindex: boolean;
  twitterCard: string;
  sitemap: boolean;
}

export interface LocaleDescriptor {
  tag: string;
  name: string;
  direction: 'ltr' | 'rtl';
}

export interface LocalesConfig {
  defaultLocale: string;
  available: LocaleDescriptor[];
}

export interface UrlsConfig {
  /** The public address of the discovery layer, ending with `/`. */
  publicUrl: string;
  /** The platform's public LDP root, ending with `/`. */
  platform: string;
  /** The base of IIIF exports: `<iiif><path>`. */
  iiif: string;
}
