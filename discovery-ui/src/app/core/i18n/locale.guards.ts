import { REQUEST, RESPONSE_INIT, inject } from '@angular/core';
import { CanActivateFn, CanMatchFn, RedirectFunction, Router, UrlSegment } from '@angular/router';

import { ConfigService } from '../config/config.service';
import { LocaleService } from './locale.service';
import { negotiateLocale } from './negotiate';

/**
 * Matches the `:lang` segment of enabled locales and switches to the locale. A tag in another
 * case (`pt-br`) is redirected to its configured spelling, so each page has one URL.
 */
export const localeMatch: CanMatchFn = async (_route, segments: UrlSegment[]) => {
  const locales = inject(LocaleService);
  const router = inject(Router);
  const tag = segments[0]?.path ?? '';
  const enabled = locales.find(tag);
  if (!enabled) {
    return false;
  }
  if (enabled !== tag) {
    return router.createUrlTree([enabled, ...segments.slice(1).map((segment) => segment.path)]);
  }
  await locales.use(enabled);
  return true;
};

/** Pages outside every locale (`/discover/nothing-here`) are shown in the default locale. */
export const defaultLocale: CanActivateFn = async () => {
  const locales = inject(LocaleService);
  if (!locales.find(locales.locale())) {
    await locales.use(inject(ConfigService).config().locales.defaultLocale);
  } else {
    await locales.use(locales.locale());
  }
  return true;
};

/**
 * Sends `/discover/` to the home page in the visitor's language: the `Accept-Language` header of
 * the request during server-side rendering (answered with a redirect), the browser's languages
 * otherwise.
 */
export const negotiatedLocale: RedirectFunction = () => {
  const config = inject(ConfigService).config();
  const request = inject(REQUEST, { optional: true });
  const response = inject(RESPONSE_INIT, { optional: true });
  if (response) {
    // the redirect depends on the visitor's languages
    response.headers = { ...(response.headers as Record<string, string>), Vary: 'Accept-Language' };
  }
  const header =
    request?.headers.get('accept-language') ??
    (typeof navigator !== 'undefined' ? navigator.languages?.join(',') : undefined);
  const tag = negotiateLocale(
    header,
    config.locales.available.map((locale) => locale.tag),
    config.locales.defaultLocale,
  );
  return inject(Router).createUrlTree([tag]);
};
