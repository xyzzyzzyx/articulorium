/** A locale the user interface can be used in (`articulorium.i18n.*` of the service). */
export interface LocaleInfo {
  /** BCP 47 language tag, for example `pt-BR`. */
  readonly tag: string;
  /** Name of the language in that language, for example `Português (Brasil)`. */
  readonly name: string;
  /** Writing direction. */
  readonly direction: 'ltr' | 'rtl';
}

/** Nested texts by key, as in `src/i18n/<tag>.json`. */
export interface Translations {
  readonly [key: string]: string | Translations;
}

/**
 * What the client starts from in one locale: inlined into `index.html` by the service
 * (`#ui-i18n`) or served by `api/i18n` and `api/i18n/<tag>`. The texts are complete: the service
 * merges each translation over the default locale's texts.
 */
export interface I18nState {
  /** The locale. */
  readonly locale: string;
  /** Its writing direction. */
  readonly direction: 'ltr' | 'rtl';
  /** The locale texts fall back to. */
  readonly defaultLocale: string;
  /** Every enabled locale, in menu order. */
  readonly locales: readonly LocaleInfo[];
  /** The texts. */
  readonly messages: Translations;
  /** Name of the cookie remembering the choice. */
  readonly cookie?: string;
}

/** Name of the locale cookie unless the service names another. */
export const DEFAULT_LOCALE_COOKIE = 'articulorium-locale';

/**
 * Finds the enabled locale best matching the requested tags, as the server does: an exact tag
 * (case-insensitive), then a locale of the same language (preferring the plain language).
 *
 * @param requested - tags in order of preference, for example `navigator.languages`
 * @param locales - the enabled locales
 * @returns the best tag, `undefined` when none matches
 */
export function negotiate(requested: readonly string[], locales: readonly LocaleInfo[]): string | undefined {
  for (const tag of requested) {
    const wanted = tag.trim().replace(/_/g, '-').toLowerCase();
    if (!wanted) {
      continue;
    }
    const exact = locales.find((locale) => locale.tag.toLowerCase() === wanted);
    if (exact) {
      return exact.tag;
    }
    const language = wanted.split('-')[0];
    const sameLanguage = locales.filter((locale) => locale.tag.toLowerCase().split('-')[0] === language);
    const plain = sameLanguage.find((locale) => !locale.tag.includes('-'));
    if (plain ?? sameLanguage[0]) {
      return (plain ?? sameLanguage[0]).tag;
    }
  }
  return undefined;
}

/**
 * Reads the start-up state the service inlined into the page.
 *
 * @param document - the document
 * @returns the state, `undefined` when the page was not rendered by the service
 */
export function inlineState(document: Document): I18nState | undefined {
  const text = document.getElementById('ui-i18n')?.textContent?.trim();
  if (!text) {
    return undefined;
  }
  try {
    const state = JSON.parse(text) as Partial<I18nState>;
    return state.locale && state.messages && Array.isArray(state.locales) ? (state as I18nState) : undefined;
  } catch {
    return undefined;
  }
}

/**
 * Builds the cookie remembering a locale for every Articulorium service (path `/`, one year).
 *
 * @param name - the cookie name
 * @param tag - the locale
 * @param secure - whether the page is served over HTTPS
 * @returns the `document.cookie` assignment
 */
export function localeCookie(name: string, tag: string, secure: boolean): string {
  return `${name}=${encodeURIComponent(tag)}; path=/; max-age=31536000; samesite=lax${secure ? '; secure' : ''}`;
}
