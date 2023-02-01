import { I18nState, LocaleInfo, inlineState, localeCookie, negotiate } from './locale';

const LOCALES: LocaleInfo[] = [
  { tag: 'en', name: 'English', direction: 'ltr' },
  { tag: 'de', name: 'Deutsch', direction: 'ltr' },
  { tag: 'pt-BR', name: 'Português (Brasil)', direction: 'ltr' },
  { tag: 'zh-CN', name: '简体中文', direction: 'ltr' },
  { tag: 'ar', name: 'العربية', direction: 'rtl' },
];

describe('negotiate', () => {
  it('matches tags exactly, then by language, like the server', () => {
    expect(negotiate(['pt-br'], LOCALES)).toBe('pt-BR');
    expect(negotiate(['de-AT', 'en'], LOCALES)).toBe('de');
    expect(negotiate(['pt-PT'], LOCALES)).toBe('pt-BR');
    expect(negotiate(['zh'], LOCALES)).toBe('zh-CN');
    expect(negotiate(['sv', 'ar-EG'], LOCALES)).toBe('ar');
  });

  it('finds nothing for unsupported languages', () => {
    expect(negotiate(['sv', 'fi'], LOCALES)).toBeUndefined();
    expect(negotiate([], LOCALES)).toBeUndefined();
  });
});

describe('inlineState', () => {
  afterEach(() => document.getElementById('ui-i18n')?.remove());

  function inline(text: string): void {
    const script = document.createElement('script');
    script.id = 'ui-i18n';
    script.type = 'application/json';
    script.textContent = text;
    document.head.appendChild(script);
  }

  it('reads the state the service inlined', () => {
    const state: I18nState = {
      locale: 'de',
      direction: 'ltr',
      defaultLocale: 'en',
      locales: LOCALES,
      messages: { nav: { browse: 'Durchsuchen <' } },
    };
    inline(JSON.stringify(state).replace(/</g, '\\u003c'));
    expect(inlineState(document)?.locale).toBe('de');
    expect(inlineState(document)?.messages).toEqual({ nav: { browse: 'Durchsuchen <' } });
  });

  it('ignores missing, malformed and incomplete states', () => {
    expect(inlineState(document)).toBeUndefined();
    inline('{ not json');
    expect(inlineState(document)).toBeUndefined();
    document.getElementById('ui-i18n')?.remove();
    inline('{"locale":"de"}');
    expect(inlineState(document)).toBeUndefined();
  });
});

describe('localeCookie', () => {
  it('is readable by every service for a year', () => {
    expect(localeCookie('articulorium-locale', 'pt-BR', false)).toBe(
      'articulorium-locale=pt-BR; path=/; max-age=31536000; samesite=lax',
    );
    expect(localeCookie('c', 'de', true)).toContain('; secure');
  });
});
