import { HttpClient } from '@angular/common/http';
import { DOCUMENT, Injectable, computed, inject, signal } from '@angular/core';
import { TranslateService } from '@ngx-translate/core';
import { firstValueFrom } from 'rxjs';

import EN from '../../../i18n/en.json';
import { DEFAULT_LOCALE_COOKIE, I18nState, LocaleInfo, Translations, inlineState, localeCookie, negotiate } from './locale';

/** The texts built into the application: the default locale, used when nothing else is available. */
export const BUILT_IN_STATE: I18nState = {
  locale: 'en',
  direction: 'ltr',
  defaultLocale: 'en',
  locales: [{ tag: 'en', name: 'English', direction: 'ltr' }],
  messages: EN as Translations,
};

/**
 * The language of the user interface.
 *
 * At start-up the locale is the one the service chose for the page (`?lang=`, the locale cookie,
 * `Accept-Language`, the default locale) and its texts are inlined into the page, so the first
 * frame is already translated and matches the server-rendered `lang` and `dir`. Without the
 * inlined state (`ng serve`) the service is asked (`api/i18n`); without the service, the built-in
 * English texts are used.
 *
 * Choosing another language stores it in a cookie every Articulorium service reads, then switches
 * the texts in place, or reloads the page when the writing direction changes.
 */
@Injectable({ providedIn: 'root' })
export class I18nService {
  private readonly translate = inject(TranslateService);

  private readonly http = inject(HttpClient);

  private readonly document = inject(DOCUMENT);

  private readonly state = signal<I18nState>(BUILT_IN_STATE);

  private readonly pluralRules = new Map<string, Intl.PluralRules>();

  /** The current locale, for example `de`. */
  readonly locale = computed(() => this.state().locale);

  /** The locale texts fall back to. */
  readonly defaultLocale = computed(() => this.state().defaultLocale);

  /** The writing direction of the current locale. */
  readonly direction = computed(() => this.state().direction);

  /** The enabled locales, in menu order. */
  readonly locales = computed<readonly LocaleInfo[]>(() => this.state().locales);

  /** The current locale's description. */
  readonly current = computed(
    () => this.locales().find((locale) => locale.tag === this.locale()) ?? BUILT_IN_STATE.locales[0],
  );

  /**
   * Loads the start-up locale and its texts.
   *
   * @returns resolved once the texts are available
   */
  async init(): Promise<void> {
    this.translate.setTranslation(BUILT_IN_STATE.locale, BUILT_IN_STATE.messages);
    this.translate.setFallbackLang(BUILT_IN_STATE.locale);
    const inline = inlineState(this.document);
    if (inline) {
      this.apply(inline);
      return;
    }
    try {
      const negotiated = await firstValueFrom(this.http.get<I18nState>('api/i18n'));
      // the service negotiated from Accept-Language; the browser's own list may name a better match
      const preferred = negotiate(navigator.languages ?? [], negotiated.locales);
      this.apply(
        preferred && preferred !== negotiated.locale && !this.hasCookie(negotiated)
          ? await firstValueFrom(this.http.get<I18nState>(`api/i18n/${encodeURIComponent(preferred)}`))
          : negotiated,
      );
    } catch (error: unknown) {
      console.warn('Using the built-in texts: api/i18n is unavailable', error);
      this.apply(BUILT_IN_STATE);
    }
  }

  /**
   * Switches to another locale and remembers the choice.
   *
   * @param tag - an enabled locale
   * @returns resolved once the texts are switched (or the page reloads)
   */
  async use(tag: string): Promise<void> {
    const target = this.locales().find((locale) => locale.tag === tag);
    if (!target || target.tag === this.locale()) {
      return;
    }
    this.document.cookie = localeCookie(this.cookieName(), target.tag, this.document.location.protocol === 'https:');
    if (target.direction !== this.direction()) {
      // layouts, overlays and the server-rendered page all follow the new direction
      this.document.location.reload();
      return;
    }
    this.apply(await firstValueFrom(this.http.get<I18nState>(`api/i18n/${encodeURIComponent(target.tag)}`)));
  }

  /**
   * A text in the current locale.
   *
   * @param key - the key, for example `nav.browse`
   * @param params - values of the text's `{{placeholders}}`
   * @returns the text (the key itself when no locale has it)
   */
  t(key: string, params?: Record<string, unknown>): string {
    return this.translate.instant(key, params) as string;
  }

  /**
   * A text that depends on a count, in the current locale's plural form: `<key>.<category>` for
   * the category `Intl.PluralRules` picks (`zero`, `one`, `two`, `few`, `many`, `other`), falling
   * back to `<key>.other`. The count is available as `{{count}}` and, formatted, `{{formatted}}`.
   *
   * @param key - the key of the plural forms, for example `search.results`
   * @param count - the count
   * @param params - other values of the text's placeholders
   * @returns the text
   */
  plural(key: string, count: number, params?: Record<string, unknown>): string {
    const locale = this.locale();
    let rules = this.pluralRules.get(locale);
    if (!rules) {
      rules = new Intl.PluralRules(locale);
      this.pluralRules.set(locale, rules);
    }
    const values = { count, formatted: new Intl.NumberFormat(locale).format(count), ...params };
    const specific = `${key}.${rules.select(count)}`;
    const text = this.t(specific, values);
    return text === specific ? this.t(`${key}.other`, values) : text;
  }

  private apply(state: I18nState): void {
    this.translate.setTranslation(state.locale, state.messages);
    this.translate.use(state.locale);
    this.state.set(state);
    const root = this.document.documentElement;
    root.lang = state.locale;
    root.dir = state.direction;
  }

  private cookieName(): string {
    return this.state().cookie || DEFAULT_LOCALE_COOKIE;
  }

  private hasCookie(state: I18nState): boolean {
    const name = state.cookie || DEFAULT_LOCALE_COOKIE;
    return this.document.cookie.split(';').some((cookie) => cookie.trim().startsWith(`${name}=`));
  }
}
