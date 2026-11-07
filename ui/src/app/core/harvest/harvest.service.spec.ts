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
});
