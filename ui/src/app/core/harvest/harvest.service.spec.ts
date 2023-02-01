import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';

import { TEST_HARVEST, provideTestConfig } from '../../testing/test-config';
import { HarvestService } from './harvest.service';

describe('HarvestService', () => {
  let http: HttpTestingController;
  let service: HarvestService;

  beforeEach(() => {
    TestBed.configureTestingModule({ providers: [provideHttpClient(), provideHttpClientTesting(), provideTestConfig()] });
    http = TestBed.inject(HttpTestingController);
    service = TestBed.inject(HarvestService);
  });

  afterEach(() => http.verify());

  it('lists the sources', () => {
    let operate: boolean | undefined;
    service.list().subscribe((list) => (operate = list.operate));
    http.expectOne({ method: 'GET', url: `${TEST_HARVEST}sources` }).flush({ sources: [], operate: true });
    expect(operate).toBe(true);
  });

  it('reads one source, escaping its identifier', () => {
    service.get('a b').subscribe();
    http.expectOne({ method: 'GET', url: `${TEST_HARVEST}sources/a%20b` }).flush({});
  });

  it('starts harvests in a mode', () => {
    service.start('repo', 'full').subscribe();
    const request = http.expectOne({ method: 'POST', url: `${TEST_HARVEST}sources/repo/harvests` });
    expect(request.request.body).toEqual({ mode: 'full' });
    request.flush({});
  });

  it('cancels the running harvest', () => {
    service.cancel('repo').subscribe();
    http.expectOne({ method: 'DELETE', url: `${TEST_HARVEST}sources/repo/harvests/running` }).flush({});
  });

  it('reads pages of the review queue', () => {
    service.classifications().subscribe();
    http
      .expectOne((request) => request.method === 'GET' && request.urlWithParams === `${TEST_HARVEST}classifications?status=open&offset=0&limit=25`)
      .flush({ classifications: [], total: 0, offset: 0, decide: false });
    service.classifications({ source: 'repo', status: 'all', offset: 50, limit: 10 }).subscribe();
    http
      .expectOne((request) => request.urlWithParams === `${TEST_HARVEST}classifications?status=all&offset=50&limit=10&source=repo`)
      .flush({ classifications: [], total: 0, offset: 50, decide: false });
  });

  it('accepts, rejects and retries classifications, escaping the source and record', () => {
    service.accept('a b', 'rec/1').subscribe();
    const accept = http.expectOne({ method: 'POST', url: `${TEST_HARVEST}classifications/a%20b/rec%2F1/acceptance` });
    expect(accept.request.body).toEqual({});
    accept.flush({});

    service.accept('repo', 'r', { collection: 'communities/lib/c', topology: 'none' }).subscribe();
    expect(http.expectOne(`${TEST_HARVEST}classifications/repo/r/acceptance`).request.body).toEqual({
      collection: 'communities/lib/c',
      topology: 'none',
    });

    service.reject('repo', 'r').subscribe();
    http.expectOne({ method: 'POST', url: `${TEST_HARVEST}classifications/repo/r/rejection` }).flush({});
    service.retry('repo', 'r').subscribe();
    http.expectOne({ method: 'POST', url: `${TEST_HARVEST}classifications/repo/r/retry` }).flush({});
  });
});
