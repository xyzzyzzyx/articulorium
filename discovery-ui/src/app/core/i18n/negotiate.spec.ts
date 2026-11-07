import { negotiateLocale } from './negotiate';

describe('negotiateLocale', () => {
  const available = ['en', 'ar', 'de', 'pt-BR', 'zh-CN'];

  it('prefers exact matches by weight', () => {
    expect(negotiateLocale('fr;q=0.9, de;q=0.8, en;q=0.5', available, 'en')).toBe('de');
    expect(negotiateLocale('zh-cn', available, 'en')).toBe('zh-CN');
  });

  it('matches the language without its region', () => {
    expect(negotiateLocale('de-AT,de;q=0.9', available, 'en')).toBe('de');
    expect(negotiateLocale('pt', available, 'en')).toBe('pt-BR');
  });

  it('falls back to the default locale', () => {
    expect(negotiateLocale('fr, *;q=0.1', available, 'en')).toBe('en');
    expect(negotiateLocale('de;q=0', available, 'ar')).toBe('ar');
    expect(negotiateLocale(null, available, 'en')).toBe('en');
    expect(negotiateLocale('', available, 'en')).toBe('en');
  });
});
