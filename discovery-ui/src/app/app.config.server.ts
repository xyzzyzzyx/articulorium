import { HTTP_TRANSFER_CACHE_ORIGIN_MAP } from '@angular/common/http';
import { ApplicationConfig, REQUEST, mergeApplicationConfig } from '@angular/core';
import { provideServerRendering, withRoutes } from '@angular/ssr';

import { appConfig } from './app.config';
import { serverRoutes } from './app.routes.server';
import { API_BASE_URL } from './core/api/api-base';
import { discoveryApiUrl } from '../server/api-url';

const serverConfig: ApplicationConfig = {
  providers: [
    provideServerRendering(withRoutes(serverRoutes)),
    { provide: API_BASE_URL, useFactory: () => discoveryApiUrl() },
    {
      // the answers fetched from the internal address are cached under the visitor's origin, which
      // is where the browser asks for them again during hydration
      provide: HTTP_TRANSFER_CACHE_ORIGIN_MAP,
      useFactory: (request: Request | null) => {
        const internal = new URL(discoveryApiUrl()).origin;
        const visitor = request ? new URL(request.url).origin : internal;
        return internal === visitor ? {} : { [internal]: visitor };
      },
      deps: [REQUEST],
    },
  ],
};

export const config = mergeApplicationConfig(appConfig, serverConfig);
