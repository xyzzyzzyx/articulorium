import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideTranslateService } from '@ngx-translate/core';

import { I18nService } from './i18n.service';
import { localeInterceptor } from './locale.interceptor';

describe('localeInterceptor', () => {
  it('sends the interface locale and keeps explicit headers', async () => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([localeInterceptor])),
        provideHttpClientTesting(),
        provideTranslateService({ fallbackLang: 'en' }),
      ],
    });
    const http = TestBed.inject(HttpClient);
    const backend = TestBed.inject(HttpTestingController);
    const script = document.createElement('script');
    script.id = 'ui-i18n';
    script.type = 'application/json';
    script.textContent = JSON.stringify({
      locale: 'de',
      direction: 'ltr',
      defaultLocale: 'en',
      locales: [{ tag: 'de', name: 'Deutsch', direction: 'ltr' }],
      messages: {},
    });
    document.head.appendChild(script);
    await TestBed.inject(I18nService).init();
    script.remove();

    http.get('http://host/platform/x').subscribe();
    expect(backend.expectOne('http://host/platform/x').request.headers.get('Accept-Language')).toBe('de, en;q=0.5');
    http.get('http://host/other', { headers: { 'Accept-Language': 'fr' } }).subscribe();
    expect(backend.expectOne('http://host/other').request.headers.get('Accept-Language')).toBe('fr');
    backend.verify();
  });
});
