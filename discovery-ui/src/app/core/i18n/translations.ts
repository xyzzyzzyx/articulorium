import { TranslationObject } from '@ngx-translate/core';

import en from '../../../i18n/en.json';

/** Texts of the interface in one language: nested groups of strings and plural forms. */
export type Translations = TranslationObject;

/** The default texts, built into the application as the last resort. */
export const DEFAULT_TRANSLATIONS: Translations = en;

/**
 * The translations of every shipped locale, each its own chunk loaded on demand (in browsers and
 * during server-side rendering alike, without an HTTP request).
 */
const LOADERS: Record<string, () => Promise<{ default: Translations }>> = {
  ar: () => import('../../../i18n/ar.json'),
  de: () => import('../../../i18n/de.json'),
  en: () => Promise.resolve({ default: en }),
  es: () => import('../../../i18n/es.json'),
  fr: () => import('../../../i18n/fr.json'),
  it: () => import('../../../i18n/it.json'),
  ja: () => import('../../../i18n/ja.json'),
  ko: () => import('../../../i18n/ko.json'),
  nl: () => import('../../../i18n/nl.json'),
  pl: () => import('../../../i18n/pl.json'),
  'pt-BR': () => import('../../../i18n/pt-BR.json'),
  'zh-CN': () => import('../../../i18n/zh-CN.json'),
};

/**
 * Loads the translations of a locale.
 *
 * @param tag - the locale's language tag
 * @returns its texts, or the default texts when the interface has no translation for it (a locale
 *   the services enable but this application does not ship)
 */
export async function loadTranslations(tag: string): Promise<Translations> {
  const loader = LOADERS[tag];
  return loader ? (await loader()).default : DEFAULT_TRANSLATIONS;
}

/** Tags of the locales this application has translations for. */
export const TRANSLATED_LOCALES = Object.keys(LOADERS);
