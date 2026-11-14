import { Routes, UrlMatchResult, UrlSegment } from '@angular/router';

import { defaultLocale, localeMatch, negotiatedLocale } from './core/i18n/locale.guards';

/**
 * Matches resource pages, `items/<path>`, whatever the number of segments of the path.
 *
 * @param segments - the segments below the locale
 */
export function itemMatcher(segments: UrlSegment[]): UrlMatchResult | null {
  if (segments.length < 2 || segments[0].path !== 'items') {
    return null;
  }
  const path = segments
    .slice(1)
    .map((segment) => segment.path)
    .join('/');
  return { consumed: segments, posParams: { path: new UrlSegment(path, {}) } };
}

/**
 * Every page lives below its locale: `/discover/<lang>/`, `/discover/<lang>/search`,
 * `/discover/<lang>/collections`, `/discover/<lang>/items/<path>`.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: negotiatedLocale },
  {
    path: ':lang',
    canMatch: [localeMatch],
    children: [
      { path: '', pathMatch: 'full', loadComponent: () => import('./pages/home/home.page').then((m) => m.HomePage) },
      {
        path: 'collections',
        loadComponent: () => import('./pages/collections/collections.page').then((m) => m.CollectionsPage),
      },
      {
        path: 'search',
        loadComponent: () => import('./pages/search/search.page').then((m) => m.SearchPage),
      },
      {
        matcher: itemMatcher,
        loadComponent: () => import('./pages/item/item.page').then((m) => m.ItemPage),
      },
      {
        path: '**',
        loadComponent: () => import('./pages/not-found/not-found.page').then((m) => m.NotFoundPage),
      },
    ],
  },
  {
    path: '**',
    canActivate: [defaultLocale],
    loadComponent: () => import('./pages/not-found/not-found.page').then((m) => m.NotFoundPage),
  },
];
