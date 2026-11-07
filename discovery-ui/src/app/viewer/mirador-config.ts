import { ViewerConfig } from '../core/config/discovery-config';

/** Mirador's languages, by the interface's locale (Mirador names Korean `kr`). */
const MIRADOR_LANGUAGES: Record<string, string> = {
  ar: 'ar',
  de: 'de',
  en: 'en',
  fr: 'fr',
  it: 'it',
  ja: 'ja',
  ko: 'kr',
  nl: 'nl',
  pl: 'pl',
  'pt-BR': 'pt-BR',
  'zh-CN': 'zh-CN',
};

/**
 * Mirador's language for a locale.
 *
 * @param locale - the interface's locale
 * @returns the language, English when Mirador has no translation for it
 */
export function miradorLanguage(locale: string): string {
  return MIRADOR_LANGUAGES[locale] ?? MIRADOR_LANGUAGES[locale.split('-')[0]] ?? 'en';
}

/** What the viewer presents and how. */
export interface ViewerOptions {
  /** The element Mirador renders into. */
  id: string;
  /** The IIIF Manifest or Collection. */
  manifest: string;
  /** Whether it is a Collection (Mirador then lets visitors pick a Manifest). */
  collection: boolean;
  /** The viewer settings of the deployment. */
  settings: ViewerConfig;
  /** The interface's locale. */
  locale: string;
  /** Whether the page is dark. */
  dark: boolean;
  /** The theme's primary color, when the deployment sets one. */
  primary?: string;
}

/**
 * Mirador's configuration: one window with the resource, no workspace chrome (the page is the
 * workspace), the interface's language and colors.
 *
 * @param options - what to present
 */
export function miradorConfig(options: ViewerOptions): Record<string, unknown> {
  const language = miradorLanguage(options.locale);
  const palette = options.primary ? { primary: { main: options.primary }, secondary: { main: options.primary } } : {};
  return {
    id: options.id,
    language,
    selectedTheme: options.dark ? 'dark' : 'light',
    themes: {
      light: { palette: { mode: 'light', ...palette } },
      dark: { palette: { mode: 'dark', ...palette } },
    },
    windows: [
      {
        manifestId: options.manifest,
        thumbnailNavigationPosition: options.settings.thumbnailNavigation,
        sideBarOpen: options.settings.sideBarOpen,
        ...(options.collection ? { collectionDialogOn: true } : {}),
      },
    ],
    window: {
      allowClose: false,
      allowMaximize: false,
      allowFullscreen: options.settings.allowFullscreen,
      allowTopMenuButton: true,
      defaultSideBarPanel: 'info',
      hideWindowTitle: false,
      panels: { info: true, attribution: true, canvas: true, annotations: true, search: true, layers: true },
    },
    workspace: {
      type: 'mosaic',
      allowNewWindows: false,
      showZoomControls: true,
      isWorkspaceAddVisible: false,
    },
    workspaceControlPanel: { enabled: false },
  };
}
