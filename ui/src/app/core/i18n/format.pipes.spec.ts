import { formatDate, formatNumber } from './format.pipes';

describe('locale formatting', () => {
  it('formats dates in the locale', () => {
    const date = '2026-10-30T12:00:00Z';
    expect(formatDate(date, 'de', 'short')).toMatch(/30\.10\.26/);
    expect(formatDate(date, 'en', 'short')).toMatch(/10\/30\/26/);
    expect(formatDate(date, 'ja', 'medium')).toMatch(/2026\/10\/30/);
  });

  it('formats numbers in the locale', () => {
    expect(formatNumber(1234567.5, 'en')).toBe('1,234,567.5');
    expect(formatNumber(1234567.5, 'de')).toBe('1.234.567,5');
    expect(formatNumber('2048', 'fr')).toBe('2 048');
  });

  it('formats missing and invalid values as empty text', () => {
    expect(formatDate(undefined, 'en')).toBe('');
    expect(formatDate('not a date', 'en')).toBe('');
    expect(formatNumber(null, 'en')).toBe('');
    expect(formatNumber('x', 'en')).toBe('');
  });
});
