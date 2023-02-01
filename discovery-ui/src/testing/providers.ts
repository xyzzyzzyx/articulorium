import { provideHttpClient } from '@angular/common/http';
import { provideHttpClientTesting } from '@angular/common/http/testing';
import { EnvironmentProviders, Provider, provideZonelessChangeDetection } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { provideTranslateService } from '@ngx-translate/core';

import { API_BASE_URL } from '../app/core/api/api-base';
import { ConfigService } from '../app/core/config/config.service';
import { DiscoveryConfig } from '../app/core/config/discovery-config';
import { LocaleService } from '../app/core/i18n/locale.service';
import { testConfig } from './config';

/** The providers of component and service tests: zoneless, mocked HTTP, a router, translations. */
export function testProviders(...extra: (Provider | EnvironmentProviders)[]): (Provider | EnvironmentProviders)[] {
  return [
    provideZonelessChangeDetection(),
    provideHttpClient(),
    provideHttpClientTesting(),
    provideRouter([]),
    provideTranslateService({ fallbackLang: 'en' }),
    { provide: API_BASE_URL, useValue: 'http://test/discover/api/' },
    ...extra,
  ];
}

/**
 * Configures the test bed with a deployment's configuration and a locale.
 *
 * @param locale - the locale to switch to
 * @param config - the configuration
 */
export async function setUpDiscovery(locale = 'en', config: DiscoveryConfig = testConfig()): Promise<DiscoveryConfig> {
  TestBed.inject(ConfigService).set(config);
  await TestBed.inject(LocaleService).use(locale);
  return config;
}
