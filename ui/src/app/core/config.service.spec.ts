import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { ConfigService, FALLBACK_CONFIG, resolvePlatformBase } from './config.service';

describe('resolvePlatformBase', () => {
  it('resolves relative URLs against the document base', () => {
    expect(resolvePlatformBase('../platform/', 'http://h:8000/ui/')).toBe('http://h:8000/platform/');
    expect(resolvePlatformBase('platform', 'http://h/')).toBe('http://h/platform/');
    expect(resolvePlatformBase('https://repo.example.org/ldp', 'http://h/ui/')).toBe('https://repo.example.org/ldp/');
    expect(resolvePlatformBase('', 'http://h:8000/ui/')).toBe('http://h:8000/platform/');
  });
});

describe('ConfigService', () => {
  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting()] });
  });

  it('loads the settings from api/config', async () => {
    const service = TestBed.inject(ConfigService);
    const loaded = service.load();
    TestBed.inject(HttpTestingController).expectOne('api/config').flush({ platformUrl: 'http://x/ldp/', title: 'Repo' });
    await loaded;

    expect(service.config().title).toBe('Repo');
    expect(service.platformBase()).toBe('http://x/ldp/');
  });

  it('keeps the defaults when the settings are unavailable', async () => {
    const service = TestBed.inject(ConfigService);
    const warn = vi.spyOn(console, 'warn').mockImplementation(() => undefined);
    const loaded = service.load();
    TestBed.inject(HttpTestingController).expectOne('api/config').flush('', { status: 503, statusText: 'Unavailable' });
    await loaded;

    expect(service.config()).toEqual(FALLBACK_CONFIG);
    warn.mockRestore();
  });
});
