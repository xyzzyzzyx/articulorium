import { Routes, UrlMatcher, UrlSegment } from '@angular/router';

/**
 * Matches `/<view>/<path...>` (and `/<view>/<param>/<path...>` with `params`), exposing the
 * remaining segments as the `path` parameter: resource paths have any number of segments.
 *
 * @param view - the first segment, for example `browse`
 * @param params - names of fixed parameters following the view segment
 * @returns the matcher
 */
export function viewMatcher(view: string, ...params: string[]): UrlMatcher {
  return (segments: UrlSegment[]) => {
    if (segments.length < 1 + params.length || segments[0].path !== view) {
      return null;
    }
    const posParams: Record<string, UrlSegment> = {};
    params.forEach((name, index) => (posParams[name] = segments[index + 1]));
    const rest = segments.slice(1 + params.length);
    posParams['path'] = new UrlSegment(rest.map((segment) => segment.path).join('/'), {});
    return { consumed: segments, posParams };
  };
}

/** The application's routes. Titles are translation keys (see `LocalizedTitleStrategy`). */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'browse' },
  {
    matcher: viewMatcher('browse'),
    title: 'route.browse',
    loadComponent: () => import('./browse/resource-page.component').then((m) => m.ResourcePageComponent),
  },
  {
    matcher: viewMatcher('versions'),
    title: 'route.versions',
    loadComponent: () => import('./history/versions-page.component').then((m) => m.VersionsPageComponent),
  },
  {
    matcher: viewMatcher('memento', 'version'),
    title: 'route.version',
    loadComponent: () => import('./history/memento-page.component').then((m) => m.MementoPageComponent),
  },
  {
    matcher: viewMatcher('fixity'),
    title: 'route.fixity',
    loadComponent: () => import('./history/fixity-page.component').then((m) => m.FixityPageComponent),
  },
  {
    matcher: viewMatcher('permissions'),
    title: 'route.permissions',
    loadComponent: () => import('./permissions/permissions-page.component').then((m) => m.PermissionsPageComponent),
  },
  {
    matcher: viewMatcher('inbox'),
    title: 'route.notifications',
    loadComponent: () => import('./notifications/notifications-page.component').then((m) => m.NotificationsPageComponent),
  },
  {
    matcher: viewMatcher('communities'),
    title: 'route.communities',
    loadComponent: () => import('./communities/communities-page.component').then((m) => m.CommunitiesPageComponent),
  },
  {
    path: 'topologies',
    title: 'route.topologies',
    loadComponent: () => import('./topology/topology-list-page.component').then((m) => m.TopologyListPageComponent),
  },
  {
    path: 'topologies/:id',
    title: 'route.topology',
    loadComponent: () => import('./topology/topology-editor-page.component').then((m) => m.TopologyEditorPageComponent),
  },
  {
    path: 'search',
    title: 'route.search',
    loadComponent: () => import('./search/search-page.component').then((m) => m.SearchPageComponent),
  },
  {
    path: 'about',
    title: 'route.about',
    loadComponent: () => import('./about/about.component').then((m) => m.AboutComponent),
  },
  { path: '**', redirectTo: 'browse' },
];
