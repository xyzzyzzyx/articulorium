import { provideHttpClient, withFetch } from '@angular/common/http';
import {
  ApplicationConfig,
  inject,
  provideAppInitializer,
  provideBrowserGlobalErrorListeners,
  provideZonelessChangeDetection,
} from '@angular/core';
import { provideClientHydration, withEventReplay, withHttpTransferCacheOptions } from '@angular/platform-browser';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';

import { routes } from './app.routes';
import { ConfigService } from './core/config/config.service';
import { ThemeService } from './core/theme/theme.service';

/** The application: zoneless, hydrated from the server-rendered page. */
export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZonelessChangeDetection(),
    provideRouter(routes, withComponentInputBinding(), withInMemoryScrolling({ scrollPositionRestoration: 'enabled', anchorScrolling: 'enabled' })),
    provideHttpClient(withFetch()),
    // answers the server fetched (configuration, searches, resources) are handed to the browser
    provideClientHydration(withEventReplay(), withHttpTransferCacheOptions({ includeHeaders: [] })),
    provideTranslateService({ fallbackLang: 'en' }),
    provideAppInitializer(() => {
      const theme = inject(ThemeService);
      return inject(ConfigService)
        .load()
        .then((config) => theme.apply(config));
    }),
  ],
};
