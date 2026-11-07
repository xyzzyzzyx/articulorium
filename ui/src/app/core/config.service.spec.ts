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

  it('resolves a configured harvest service', async () => {
    const service = TestBed.inject(ConfigService);
    const loaded = service.load();
    TestBed.inject(HttpTestingController)
      .expectOne('api/config')
      .flush({ platformUrl: '../platform/', title: 'Repo', harvestUrl: 'https://harvest.example/api' });
    await loaded;

    expect(service.harvestBase()).toBe('https://harvest.example/api/');
  });

  it('resolves the API documentation, topology, transform and harvest services and the community and profile paths', async () => {
    const service = TestBed.inject(ConfigService);
    const loaded = service.load();
    TestBed.inject(HttpTestingController)
      .expectOne('api/config')
      .flush({ platformUrl: '../platform/', title: 'Repo', apiDocsUrl: '../swagger-ui.html', communitiesPath: '/orgs/', profilesPath: 'people/' });
    await loaded;

    expect(service.apiDocs()).toBe(new URL('../swagger-ui.html', document.baseURI).toString());
    expect(service.topologyBase()).toBe(new URL('../topology/', document.baseURI).toString());
    expect(service.transformBase()).toBe(new URL('../transform/', document.baseURI).toString());
    expect(service.harvestBase()).toBe(new URL('../harvest/', document.baseURI).toString());
    expect(service.communitiesPath()).toBe('orgs');
    expect(service.profilesPath()).toBe('people');
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

  it('resolves the OpenID Connect issuer and falls back to the default sign-in', async () => {
    const service = TestBed.inject(ConfigService);
    expect(service.auth().provider).toBe('oidc');
    expect(service.auth().oidc.authority).toBe(new URL('../auth/realms/articulorium', document.baseURI).toString());
    expect(service.auth().oidc.clientId).toBe('articulorium-ui');

    const loaded = service.load();
    TestBed.inject(HttpTestingController)
      .expectOne('api/config')
      .flush({
        platformUrl: '../platform/',
        title: 'Repo',
        auth: { provider: 'oidc', oidc: { authority: 'https://idp.example/realms/r/', clientId: 'lib', scope: 'openid' } },
      });
    await loaded;
    expect(service.auth()).toEqual({
      provider: 'oidc',
      oidc: { authority: 'https://idp.example/realms/r', clientId: 'lib', scope: 'openid' },
    });
  });
});
