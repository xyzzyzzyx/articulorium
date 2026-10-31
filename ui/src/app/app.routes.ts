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

/** The application's routes. */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'browse' },
  {
    matcher: viewMatcher('browse'),
    title: 'Browse',
    loadComponent: () => import('./browse/resource-page.component').then((m) => m.ResourcePageComponent),
  },
  {
    matcher: viewMatcher('versions'),
    title: 'Versions',
    loadComponent: () => import('./history/versions-page.component').then((m) => m.VersionsPageComponent),
  },
  {
    matcher: viewMatcher('memento', 'version'),
    title: 'Version',
    loadComponent: () => import('./history/memento-page.component').then((m) => m.MementoPageComponent),
  },
  {
    matcher: viewMatcher('fixity'),
    title: 'Fixity',
    loadComponent: () => import('./history/fixity-page.component').then((m) => m.FixityPageComponent),
  },
  {
    matcher: viewMatcher('permissions'),
    title: 'Permissions',
    loadComponent: () => import('./permissions/permissions-page.component').then((m) => m.PermissionsPageComponent),
  },
  {
    matcher: viewMatcher('inbox'),
    title: 'Notifications',
    loadComponent: () => import('./notifications/notifications-page.component').then((m) => m.NotificationsPageComponent),
  },
  {
    matcher: viewMatcher('communities'),
    title: 'Communities',
    loadComponent: () => import('./communities/communities-page.component').then((m) => m.CommunitiesPageComponent),
  },
  {
    path: 'topologies',
    title: 'Topologies',
    loadComponent: () => import('./topology/topology-list-page.component').then((m) => m.TopologyListPageComponent),
  },
  {
    path: 'topologies/:id',
    title: 'Topology',
    loadComponent: () => import('./topology/topology-editor-page.component').then((m) => m.TopologyEditorPageComponent),
  },
  {
    path: 'search',
    title: 'Search',
    loadComponent: () => import('./search/search-page.component').then((m) => m.SearchPageComponent),
  },
  {
    path: 'about',
    title: 'About',
    loadComponent: () => import('./about/about.component').then((m) => m.AboutComponent),
  },
  { path: '**', redirectTo: 'browse' },
];
