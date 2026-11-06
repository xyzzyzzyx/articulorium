import { Pipe, PipeTransform, inject } from '@angular/core';

import { I18nService } from './i18n.service';

/** Date styles of {@link LocalizedDatePipe}. */
export type DateStyle = 'short' | 'medium' | 'long';

const dateFormats = new Map<string, Intl.DateTimeFormat>();

const numberFormats = new Map<string, Intl.NumberFormat>();

/**
 * Formats a date and time in a locale.
 *
 * @param value - an ISO 8601 string, a `Date` or epoch milliseconds
 * @param locale - the locale
 * @param style - how much detail
 * @returns the formatted value, or an empty string for missing and invalid values
 */
export function formatDate(value: string | number | Date | null | undefined, locale: string, style: DateStyle = 'medium'): string {
  if (value === null || value === undefined || value === '') {
    return '';
  }
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) {
    return '';
  }
  const key = `${locale}|${style}`;
  let format = dateFormats.get(key);
  if (!format) {
    format = new Intl.DateTimeFormat(locale, { dateStyle: style, timeStyle: style === 'long' ? 'medium' : style });
    dateFormats.set(key, format);
  }
  return format.format(date);
}

/**
 * Formats a number in a locale.
 *
 * @param value - the number (or its string form)
 * @param locale - the locale
 * @returns the formatted value, or an empty string for missing and invalid values
 */
export function formatNumber(value: number | string | null | undefined, locale: string): string {
  if (value === null || value === undefined || value === '') {
    return '';
  }
  const number = typeof value === 'number' ? value : Number(value);
  if (!Number.isFinite(number)) {
    return '';
  }
  let format = numberFormats.get(locale);
  if (!format) {
    format = new Intl.NumberFormat(locale);
    numberFormats.set(locale, format);
  }
  return format.format(number);
}

/**
 * `{{ value | l10nDate: 'short' }}`: a date and time in the user interface's locale (follows
 * language switches).
 */
@Pipe({ name: 'l10nDate', pure: false })
export class LocalizedDatePipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  /**
   * Formats a date.
   *
   * @param value - the date
   * @param style - `short`, `medium` (default) or `long`
   * @returns the formatted date
   */
  transform(value: string | number | Date | null | undefined, style: DateStyle = 'medium'): string {
    return formatDate(value, this.i18n.locale(), style);
  }
}

/**
 * `{{ value | l10nNumber }}`: a number in the user interface's locale (follows language
 * switches).
 */
@Pipe({ name: 'l10nNumber', pure: false })
export class LocalizedNumberPipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  /**
   * Formats a number.
   *
   * @param value - the number
   * @returns the formatted number
   */
  transform(value: number | string | null | undefined): string {
    return formatNumber(value, this.i18n.locale());
  }
}

/**
 * `{{ 'search.results' | tplural: count }}`: a text in the plural form of the user interface's
 * locale (see {@link I18nService.plural}).
 */
@Pipe({ name: 'tplural', pure: false })
export class PluralPipe implements PipeTransform {
  private readonly i18n = inject(I18nService);

  /**
   * Picks the plural form.
   *
   * @param key - the key of the plural forms
   * @param count - the count
   * @param params - other placeholder values
   * @returns the text
   */
  transform(key: string, count: number, params?: Record<string, unknown>): string {
    return this.i18n.plural(key, count, params);
  }
}
