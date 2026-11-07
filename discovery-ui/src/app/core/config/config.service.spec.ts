import { HttpTestingController } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { testConfig } from '../../../testing/config';
import { testProviders } from '../../../testing/providers';
import { ConfigService } from './config.service';
import { FALLBACK_CONFIG } from './fallback-config';

describe('ConfigService', () => {
  beforeEach(() => TestBed.configureTestingModule({ providers: testProviders() }));

  it('loads the configuration of the discovery service', async () => {
    const service = TestBed.inject(ConfigService);
    const loading = service.load();
    TestBed.inject(HttpTestingController).expectOne('http://test/discover/api/config').flush(testConfig());

    await loading;
    expect(service.config().site.name.value).toBe('Articulorium');
    expect(service.unavailable()).toBe(false);
  });

  it('falls back to a minimal configuration while the service is unavailable', async () => {
    vi.spyOn(console, 'error').mockImplementation(() => undefined);
    const service = TestBed.inject(ConfigService);
    const loading = service.load();
    TestBed.inject(HttpTestingController)
      .expectOne('http://test/discover/api/config')
      .flush({ status: 503 }, { status: 503, statusText: 'Service Unavailable' });

    await loading;
    expect(service.config()).toBe(FALLBACK_CONFIG);
    expect(service.unavailable()).toBe(true);
  });
});
