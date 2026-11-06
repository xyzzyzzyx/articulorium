import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  Provider,
  inject,
  isDevMode,
  provideAppInitializer,
  provideZonelessChangeDetection,
} from '@angular/core';
import { MatPaginatorIntl } from '@angular/material/paginator';
import { TitleStrategy, provideRouter, withComponentInputBinding } from '@angular/router';
import { provideEffects } from '@ngrx/effects';
import { provideRouterStore } from '@ngrx/router-store';
import { provideStore } from '@ngrx/store';
import { provideStoreDevtools } from '@ngrx/store-devtools';
import { provideTranslateService } from '@ngx-translate/core';

import { routes } from './app.routes';
import { authInterceptor } from './core/auth/auth.interceptor';
import { AuthService } from './core/auth/auth.service';
import { ConfigService } from './core/config.service';
import { I18nService } from './core/i18n/i18n.service';
import { localeInterceptor } from './core/i18n/locale.interceptor';
import { LocalizedPaginatorIntl } from './core/i18n/paginator-intl';
import { LocalizedTitleStrategy } from './core/i18n/title.strategy';

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
      // the user's locale goes to every service; the signed-in user's token to the repository's
      // services only
      provideHttpClient(withFetch(), withInterceptors([localeInterceptor, authInterceptor])),
      // texts come from the start-up state (I18nService), not from a loader
      provideTranslateService({ fallbackLang: 'en', lang: 'en' }),
      // route parameters (the resource path, the version) are bound to component inputs
      provideRouter(routes, withComponentInputBinding()),
      // route titles are translation keys; Material's built-in texts are translated too
      { provide: TitleStrategy, useExisting: LocalizedTitleStrategy },
      { provide: MatPaginatorIntl, useClass: LocalizedPaginatorIntl },
      // the locale and its texts and the settings, then the sign-in the settings choose (which may
      // complete a redirect from the identity provider) - all before the first navigation, so the
      // first frame is rendered in the right language
      provideAppInitializer(() => {
        const config = inject(ConfigService);
        const auth = inject(AuthService);
        const i18n = inject(I18nService);
        return Promise.all([i18n.init(), config.load()]).then(() => auth.init());
      }),
      provideEffects(),
      provideRouterStore(),
      provideStore(),
      provideStoreDevtools({ maxAge: 25, logOnly: !isDevMode() }),
    ],
  };
};
