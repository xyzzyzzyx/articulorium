import { DOCUMENT } from '@angular/common';
import { Injectable, computed, inject, signal } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';

import { ConfigService } from '../config/config.service';
import { LocaleDescriptor, LocalizedText } from '../config/discovery-config';
import { DEFAULT_TRANSLATIONS, loadTranslations } from './translations';

/**
 * The visitor's locale. Every page lives below its locale (`/discover/<tag>/...`), so each
 * language has its own URL for search engines; the `:lang` route sets the locale here, which loads
 * its texts and sets the document's `lang` and `dir`.
 */
@Injectable({ providedIn: 'root' })
export class LocaleService {
  private readonly translate = inject(TranslateService);

  private readonly config = inject(ConfigService);

  private readonly document = inject(DOCUMENT);

  private readonly pluralRules = new Map<string, Intl.PluralRules>();

  private readonly current = signal<string>('en');

  /** The current locale's tag. */
  readonly locale = this.current.asReadonly();

  /** The current locale's descriptor. */
  readonly descriptor = computed<LocaleDescriptor>(
    () =>
      this.config.config().locales.available.find((locale) => locale.tag === this.current()) ?? {
        tag: this.current(),
        name: this.current(),
        direction: 'ltr',
      },
  );

  /** The current writing direction. */
  readonly direction = computed(() => this.descriptor().direction);

  constructor() {
    this.translate.setTranslation('en', DEFAULT_TRANSLATIONS);
    this.translate.setFallbackLang('en');
  }

  /**
   * Finds an enabled locale.
   *
   * @param tag - a tag in any case
   * @returns the enabled locale's tag as configured, `undefined` when it is not enabled
   */
  find(tag: string): string | undefined {
    const lower = tag.toLowerCase();
    return this.config.config().locales.available.find((locale) => locale.tag.toLowerCase() === lower)?.tag;
  }

  /**
   * Switches to a locale: loads its texts, then sets the document's language and direction.
   *
   * @param tag - an enabled locale's tag
   */
  async use(tag: string): Promise<void> {
    if (!this.translate.getLangs().includes(tag)) {
      this.translate.setTranslation(tag, await loadTranslations(tag), false);
    }
    this.translate.use(tag);
    this.current.set(tag);
    const html = this.document.documentElement;
    html.setAttribute('lang', tag);
    html.setAttribute('dir', this.direction());
  }

  /**
   * A text of the interface.
   *
   * @param key - its key, for example `search.title`
   * @param params - values of its `{{placeholders}}`
   */
  t(key: string, params?: Record<string, unknown>): string {
    return this.translate.instant(key, params) as string;
  }

  /**
   * A text that depends on a count, in the current locale's plural form.
   *
   * @param key - the key of the plural forms (`<key>.one`, `<key>.other`, ...)
   * @param count - the count
   * @param params - values of other placeholders; `count` and `formatted` are added
   */
  plural(key: string, count: number, params?: Record<string, unknown>): string {
    const locale = this.current();
    let rules = this.pluralRules.get(locale);
    if (!rules) {
      rules = new Intl.PluralRules(locale);
      this.pluralRules.set(locale, rules);
    }
    const values = { ...params, count, formatted: this.number(count) };
    const category = `${key}.${rules.select(count)}`;
    const text = this.translate.instant(category, values) as string;
    return text === category ? (this.translate.instant(`${key}.other`, values) as string) : text;
  }

  /**
   * Text of the settings in the current language: a translation key of the interface, the text's
   * translation for this language (or its language without region), or its value.
   *
   * @param text - the text, may be missing
   * @param fallbackKey - a key used when the text is missing
   */
  text(text: LocalizedText | null | undefined, fallbackKey?: string): string {
    const locale = this.current();
    if (text?.translations) {
      const translation =
        text.translations[locale] ??
        Object.entries(text.translations).find(([tag]) => tag.split('-')[0] === locale.split('-')[0])?.[1];
      if (translation) {
        return translation;
      }
    }
    if (text?.key) {
      const translated = this.t(text.key);
      if (translated !== text.key) {
        return translated;
      }
    }
    if (text?.value) {
      return text.value;
    }
    return fallbackKey ? this.t(fallbackKey) : '';
  }

  /** Formats a number in the current locale. */
  number(value: number): string {
    return new Intl.NumberFormat(this.current()).format(value);
  }

  /**
   * Formats an ISO date in the current locale.
   *
   * @param value - an ISO 8601 date or date-time
   * @returns the formatted date, or the value itself when it is not a date
   */
  date(value: string | null | undefined): string {
    if (!value) {
      return '';
    }
    const date = new Date(value);
    if (Number.isNaN(date.getTime())) {
      return value;
    }
    // dates at midnight UTC are days (dates of publication), not instants
    const day = /T00:00:00(\.0+)?Z$/.test(value) || /^\d{4}-\d{2}-\d{2}$/.test(value);
    return new Intl.DateTimeFormat(this.current(), {
      dateStyle: 'long',
      ...(day ? { timeZone: 'UTC' } : {}),
    }).format(date);
  }

  /** The name of a language in the current locale (`fr` → `French`, `Französisch`). */
  languageName(tag: string): string {
    try {
      return new Intl.DisplayNames([this.current()], { type: 'language' }).of(tag) ?? tag;
    } catch {
      return tag;
    }
  }
}
