import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { TranslateService, provideTranslateService } from '@ngx-translate/core';

import { BUILT_IN_STATE, I18nService } from './i18n.service';
import { I18nState } from './locale';

const GERMAN: I18nState = {
  locale: 'de',
  direction: 'ltr',
  defaultLocale: 'en',
  locales: [
    { tag: 'en', name: 'English', direction: 'ltr' },
    { tag: 'de', name: 'Deutsch', direction: 'ltr' },
    { tag: 'fr', name: 'Français', direction: 'ltr' },
    { tag: 'ar', name: 'العربية', direction: 'rtl' },
  ],
  messages: { nav: { browse: 'Durchsuchen' }, search: { results: { one: '{{formatted}} Treffer', other: '{{formatted}} Treffer' } } },
  cookie: 'articulorium-locale',
};

describe('I18nService', () => {
  let http: HttpTestingController;
  let service: I18nService;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [provideHttpClient(), provideHttpClientTesting(), provideTranslateService({ fallbackLang: 'en' })],
    });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(I18nService);
  });

  afterEach(() => {
    document.getElementById('ui-i18n')?.remove();
    document.documentElement.lang = 'en';
    document.documentElement.dir = 'ltr';
    document.cookie = 'articulorium-locale=; path=/; max-age=0';
    http.verify();
  });

  function inline(state: I18nState): void {
    const script = document.createElement('script');
    script.id = 'ui-i18n';
    script.type = 'application/json';
    script.textContent = JSON.stringify(state);
    document.head.appendChild(script);
  }

  it('starts in the locale the server rendered, without a request', async () => {
    inline(GERMAN);
    await service.init();

    expect(service.locale()).toBe('de');
    expect(service.t('nav.browse')).toBe('Durchsuchen');
    // texts the translation lacks fall back to the built-in English
    expect(service.t('nav.search')).toBe('Search');
    expect(document.documentElement.lang).toBe('de');
    expect(service.locales().map((locale) => locale.tag)).toEqual(['en', 'de', 'fr', 'ar']);
  });

  it('asks the service without an inlined state, preferring the browser languages', async () => {
    const init = service.init();
    http.expectOne('api/i18n').flush(GERMAN);
    // the service negotiated German from Accept-Language; the browser (jsdom: en-US) prefers English
    await new Promise((resolve) => setTimeout(resolve));
    http.expectOne('api/i18n/en').flush({ ...GERMAN, locale: 'en', messages: BUILT_IN_STATE.messages });
    await init;
    expect(service.locale()).toBe('en');
    expect(service.locales().length).toBe(4);
  });

  it('keeps the negotiated locale once the user chose one', async () => {
    document.cookie = 'articulorium-locale=de; path=/';
    const init = service.init();
    http.expectOne('api/i18n').flush(GERMAN);
    await init;
    expect(service.locale()).toBe('de');
  });

  it('falls back to the built-in texts without the service', async () => {
    const init = service.init();
    http.expectOne('api/i18n').flush('down', { status: 503, statusText: 'Unavailable' });
    await init;

    expect(service.locale()).toBe('en');
    expect(service.t('nav.browse')).toBe('Browse');
  });

  it('switches language in place and remembers the choice', async () => {
    inline(GERMAN);
    await service.init();

    const switching = service.use('fr');
    http.expectOne('api/i18n/fr').flush({ ...GERMAN, locale: 'fr', messages: { nav: { browse: 'Parcourir' } } });
    await switching;

    expect(service.locale()).toBe('fr');
    expect(service.t('nav.browse')).toBe('Parcourir');
    expect(TestBed.inject(TranslateService).getCurrentLang()).toBe('fr');
    expect(document.documentElement.lang).toBe('fr');
    expect(document.cookie).toContain('articulorium-locale=fr');
  });

  it('ignores locales that are not enabled', async () => {
    inline(GERMAN);
    await service.init();
    await service.use('ja');
    expect(service.locale()).toBe('de');
  });

  it('picks plural forms of the current locale', async () => {
    const init = service.init();
    http.expectOne('api/i18n').flush('down', { status: 503, statusText: 'Unavailable' });
    await init;
    expect(service.plural('search.results', 1)).toBe('1 result');
    expect(service.plural('search.results', 1234)).toBe('1,234 results');
  });
});
