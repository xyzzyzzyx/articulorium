import EN from '../../../i18n/en.json';
import { Translations } from './locale';

/**
 * Looks up a text of a translation, interpolating `{{placeholders}}` like ngx-translate.
 *
 * @param messages - the translation
 * @param key - the dotted key
 * @param params - placeholder values
 * @returns the text, or the key when the translation lacks it
 */
export function lookup(messages: Translations, key: string, params?: Record<string, unknown>): string {
  let value: string | Translations | undefined = messages;
  for (const part of key.split('.')) {
    value = typeof value === 'object' ? value[part] : undefined;
  }
  if (typeof value !== 'string') {
    return key;
  }
  return value.replace(/\{\{\s*([\w.]+)\s*\}\}/g, (match, name: string) =>
    params && name in params ? String(params[name]) : match,
  );
}

/**
 * The built-in English texts, for code that runs without the translation service (pure
 * functions' defaults, tests).
 *
 * @param key - the key
 * @param params - placeholder values
 * @returns the English text
 */
export function english(key: string, params?: Record<string, unknown>): string {
  return lookup(EN as Translations, key, params);
}
