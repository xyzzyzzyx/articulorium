import { DOCUMENT } from '@angular/common';
import { TestBed } from '@angular/core/testing';

import { setUpDiscovery, testProviders } from '../../../testing/providers';
import { LocaleService } from './locale.service';

describe('LocaleService', () => {
  let locales: LocaleService;

  beforeEach(async () => {
    TestBed.configureTestingModule({ providers: testProviders() });
    await setUpDiscovery('de');
    locales = TestBed.inject(LocaleService);
  });

  it('switches texts, language and direction of the document', async () => {
    const html = TestBed.inject(DOCUMENT).documentElement;
    expect(locales.t('nav.home')).toBe('Startseite');
    expect(html.getAttribute('lang')).toBe('de');
    expect(html.getAttribute('dir')).toBe('ltr');

    await locales.use('ar');
    expect(locales.direction()).toBe('rtl');
    expect(html.getAttribute('dir')).toBe('rtl');
  });

  it('finds enabled locales in any case', () => {
    expect(locales.find('PT-br')).toBe('pt-BR');
    expect(locales.find('fr')).toBeUndefined();
  });

  it('picks plural forms of the locale', async () => {
    expect(locales.plural('search.count', 1)).toBe('1 Ergebnis');
    expect(locales.plural('search.count', 1234)).toBe('1.234 Ergebnisse');
    await locales.use('ar');
    expect(locales.plural('search.count', 2)).toBe('نتيجتان');
  });

  it('translates texts of the settings', () => {
    expect(locales.text({ value: 'About', translations: { de: 'Über uns' } })).toBe('Über uns');
    expect(locales.text({ value: 'About', translations: { 'de-CH': 'Über' } })).toBe('Über');
    expect(locales.text({ key: 'kinds.collection', value: 'x' })).toBe('Sammlung');
    expect(locales.text({ key: 'no.such.key', value: 'Fallback' })).toBe('Fallback');
    expect(locales.text(undefined, 'facets.type')).toBe('Art');
  });

  it('formats dates of publication as days', () => {
    expect(locales.date('1851-10-18T00:00:00Z')).toBe('18. Oktober 1851');
    expect(locales.date('not a date')).toBe('not a date');
    expect(locales.languageName('fr')).toBe('Französisch');
  });
});
