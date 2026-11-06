import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';

import { I18nService } from './i18n.service';

/**
 * Sends the user interface's locale as `Accept-Language` (with the browser's default as a weaker
 * preference), so every service answers error messages, translated labels and IIIF documents in
 * the language the user chose, also when it is not reached through the gateway's origin (where
 * the locale cookie travels too). Requests that set the header themselves are left alone.
 *
 * @param request - the outgoing request
 * @param next - the next handler
 * @returns the response stream
 */
export const localeInterceptor: HttpInterceptorFn = (request, next) => {
  if (request.headers.has('Accept-Language')) {
    return next(request);
  }
  const i18n = inject(I18nService);
  const locale = i18n.locale();
  const fallback = i18n.defaultLocale() === locale ? undefined : i18n.defaultLocale();
  const value = fallback ? `${locale}, ${fallback};q=0.5` : `${locale}, *;q=0.1`;
  return next(request.clone({ setHeaders: { 'Accept-Language': value } }));
};
