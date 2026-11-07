import { RenderMode, ServerRoute } from '@angular/ssr';

/**
 * Every page is rendered on the server for each request: collections change, pages carry the
 * visitor's language and only show what the visitor may read.
 */
export const serverRoutes: ServerRoute[] = [{ path: '**', renderMode: RenderMode.Server }];
