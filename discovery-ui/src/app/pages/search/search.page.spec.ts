import { HttpTestingController } from '@angular/common/http/testing';
import { RESPONSE_INIT } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { setUpDiscovery, testProviders } from '../../../testing/providers';
import { SearchResponse } from '../../core/api/discovery-api';
import { SearchPage } from './search.page';

const RESPONSE: SearchResponse = {
  query: 'whale',
  page: 1,
  size: 20,
  total: 45,
  sort: 'relevance',
  results: [
    {
      path: 'c/rare/moby',
      title: 'Moby Dick',
      description: 'A whale of a tale',
      kind: 'OBJECT',
      types: [],
      date: '1851-10-18T00:00:00Z',
      thumbnail: 'http://x/t.jpg',
    },
  ],
  hidden: 1,
  facets: [
    {
      id: 'type',
      values: [
        { value: 'http://purl.org/ontology/bibo/Book', count: 12, selected: true },
        { value: 'http://example.org/Other', count: 3, selected: false },
      ],
    },
    { id: 'language', values: [{ value: 'fr', count: 2, selected: false }] },
    { id: 'year', values: [{ value: '1850-1859', count: 1, selected: false }] },
  ],
  filters: [],
};

describe('SearchPage', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  const response: { status?: number } = {};

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: testProviders(provideRouter([{ path: ':lang/search', component: SearchPage }]), {
        provide: RESPONSE_INIT,
        useValue: response,
      }),
    });
    await setUpDiscovery('en');
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  const open = async (url: string, body: SearchResponse | null = RESPONSE, status = 200) => {
    const navigation = harness.navigateByUrl(url);
    await new Promise((resolve) => setTimeout(resolve));
    const request = http.expectOne((r) => r.url === 'http://test/discover/api/search');
    if (body) {
      request.flush(body);
    } else {
      request.flush({ status }, { status, statusText: 'Error' });
    }
    await navigation;
    await harness.fixture.whenStable();
    return { request: request.request, element: harness.routeNativeElement as HTMLElement };
  };

  it('searches what the URL describes', async () => {
    const { request } = await open('/en/search?q=whale&page=2&sort=title&f=type:http://purl.org/ontology/bibo/Book&filter=digitized');

    expect(request.params.get('q')).toBe('whale');
    expect(request.params.get('page')).toBe('2');
    expect(request.params.get('sort')).toBe('title');
    expect(request.params.getAll('f')).toEqual(['type:http://purl.org/ontology/bibo/Book']);
    expect(request.params.getAll('filter')).toEqual(['digitized']);
    expect(request.params.get('lang')).toBe('en');
  });

  it('lists results, facets and refinements as links', async () => {
    const { element } = await open('/en/search?q=whale&f=type:http://purl.org/ontology/bibo/Book');

    expect(element.querySelector('h1')?.textContent).toContain('Results for “whale”');
    expect(element.querySelector('#disc-results-heading')?.textContent).toContain('45 results');
    const result = element.querySelector('.disc-result h3 a') as HTMLAnchorElement;
    expect(result.textContent).toContain('Moby Dick');
    expect(result.getAttribute('href')).toBe('/en/items/c/rare/moby');
    expect(element.querySelector('.disc-result-thumb')?.getAttribute('alt')).toBe('');

    const values = Array.from(element.querySelectorAll('disc-facet-panel details .disc-values a')).map(
      (a) => `${a.querySelector('.disc-value-label')?.textContent?.trim()} ${a.querySelector('.disc-count')?.textContent?.trim()}`,
    );
    expect(values).toEqual(['Book 12', 'Other 3', 'French 2', '1850–1859 1']);
    expect(element.querySelector('disc-facet-panel .disc-selected .disc-visually-hidden')?.textContent).toBe('(selected)');
    const selected = element.querySelector('disc-facet-panel a[aria-current="true"]') as HTMLAnchorElement;
    expect(selected.getAttribute('href')).toBe('/en/search?q=whale');

    const remove = element.querySelector('.disc-active a') as HTMLAnchorElement;
    expect(remove.getAttribute('aria-label')).toBe('Remove Type: Book');

    const next = element.querySelector('a[rel="next"]') as HTMLAnchorElement;
    expect(next.getAttribute('href')).toContain('page=2');
    expect(element.querySelector('.disc-page-of')?.textContent).toContain('Page 1 of 3');
  });

  it('says when nothing matches', async () => {
    const { element } = await open('/en/search?q=nothing', { ...RESPONSE, total: 0, results: [] });
    expect(element.querySelector('.disc-message')?.textContent).toContain('Nothing matches this search');
  });

  it('reports an unavailable index with HTTP 503', async () => {
    const { element } = await open('/en/search?q=whale', null, 503);
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('Search is unavailable');
    expect(response.status).toBe(503);
  });
});
