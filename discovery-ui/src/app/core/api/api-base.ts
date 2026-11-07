import { DOCUMENT } from '@angular/common';
import { InjectionToken, inject } from '@angular/core';

/**
 * Where the discovery API is reached, as an absolute URL ending with `/`.
 *
 * In browsers it is `api/` next to the base href (`http://<host>/discover/api/` through the
 * gateway). During server-side rendering it is the internal address of the same path
 * (`DISCOVERY_API_URL`, see `app.config.server.ts`), and `HTTP_TRANSFER_CACHE_ORIGIN_MAP` maps
 * its origin to the visitor's, so the answers the server fetched are handed to the browser
 * instead of being fetched again during hydration.
 */
export const API_BASE_URL = new InjectionToken<string>('API_BASE_URL', {
  providedIn: 'root',
  factory: () => new URL('api/', inject(DOCUMENT).baseURI).href,
});
