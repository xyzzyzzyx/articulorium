import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  Provider,
  inject,
  isDevMode,
  provideAppInitializer,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideRouterStore } from '@ngrx/router-store';
import { provideStore } from '@ngrx/store';
import { provideStoreDevtools } from '@ngrx/store-devtools';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { ConfigService } from './core/config.service';

/**
 * Builds the application configuration.
 *
 * @param providers - providers computed before bootstrap (the client description)
 * @returns the configuration
 */
export const appConfig = (providers: Provider[]): ApplicationConfig => {
  return {
    providers: [
      ...providers,
      provideZonelessChangeDetection(),
      // the signed-in user's token goes to the repository's services only
      provideHttpClient(withFetch(), withInterceptors([authInterceptor])),
      // route parameters (the resource path, the version) are bound to component inputs
      provideRouter(routes, withComponentInputBinding()),
      provideAppInitializer(() => inject(ConfigService).load()),
      provideEffects(),
      provideRouterStore(),
      provideStore(),
      provideStoreDevtools({ maxAge: 25, logOnly: !isDevMode() }),
    ],
  };
};
