import { EnvironmentProviders, Provider, inject, provideEnvironmentInitializer } from '@angular/core';
import { TranslateService, provideTranslateService } from '@ngx-translate/core';

import { BUILT_IN_STATE } from '../core/i18n/i18n.service';
import { Translations } from '../core/i18n/locale';

/**
 * Provides the translation service with the built-in English texts (or other texts), so
 * component tests render and assert the same English texts as before translation.
 *
 * @param messages - the texts, English by default
 * @param locale - their locale
 * @returns the providers
 */
export function provideTestI18n(
  messages: Translations = BUILT_IN_STATE.messages,
  locale = 'en',
): (Provider | EnvironmentProviders)[] {
  return [
    provideTranslateService({ fallbackLang: 'en', lang: locale }),
    provideEnvironmentInitializer(() => {
      const translate = inject(TranslateService);
      translate.setTranslation('en', BUILT_IN_STATE.messages);
      translate.setTranslation(locale, messages, true);
      translate.use(locale);
    }),
  ];
}
