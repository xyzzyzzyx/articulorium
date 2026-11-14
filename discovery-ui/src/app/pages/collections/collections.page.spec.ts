import { DOCUMENT } from '@angular/common';
import { HttpTestingController, TestRequest } from '@angular/common/http/testing';
import { RESPONSE_INIT } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { testConfig } from '../../../testing/config';
import { setUpDiscovery, testProviders } from '../../../testing/providers';
import { SearchResponse, SearchResult } from '../../core/api/discovery-api';
import { CollectionsPage } from './collections.page';

const entry = (path: string, title: string, kind: SearchResult['kind']): SearchResult => ({ path, title, kind, types: [] });

const answer = (results: SearchResult[], total = results.length, page = 1): SearchResponse => ({
  query: '',
  page,
  size: 50,
  total,
  results,
  hidden: 0,
  facets: [],
  filters: [],
});

describe('CollectionsPage', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  let response: { status?: number };

  const configure = async (enabled = true) => {
    response = {};
    TestBed.configureTestingModule({
      providers: testProviders(provideRouter([{ path: ':lang/collections', component: CollectionsPage }]), {
        provide: RESPONSE_INIT,
        useValue: response,
      }),
    });
    const config = testConfig();
    config.layout.collections.enabled = enabled;
    await setUpDiscovery('en', config);
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  };

  const search = (within: string): TestRequest =>
    http.expectOne((r) => r.url === 'http://test/discover/api/search' && r.params.get('within') === within);

  const settle = async () => {
    await harness.fixture.whenStable();
    harness.fixture.detectChanges();
    await harness.fixture.whenStable();
  };

  it('lists the top-level communities and collections and expands them in place', async () => {
    await configure();
    await harness.navigateByUrl('/en/collections');
    await settle();
    const top = search('communities');
    expect(top.request.params.getAll('kind')).toEqual(['community', 'collection']);
    expect(top.request.params.get('sort')).toBe('title');
    expect(top.request.params.get('size')).toBe('50');
    top.flush(answer([entry('communities/library', 'Library', 'COMMUNITY'), entry('communities/maps', 'Maps', 'COLLECTION')], 51));
    await settle();

    const element = harness.routeNativeElement as HTMLElement;
    expect(element.querySelector('h1')?.textContent).toBe('Communities and collections');
    const links = Array.from(element.querySelectorAll('.disc-tree-link'));
    expect(links.map((a) => a.textContent)).toEqual(['Library', 'Maps']);
    expect(links[0].getAttribute('href')).toBe('/en/items/communities/library');
    expect(element.querySelector('.disc-tree-more')?.textContent?.trim()).toBe('Show more (2 of 51)');

    const toggle = element.querySelector<HTMLButtonElement>('.disc-tree-toggle')!;
    expect(toggle.getAttribute('aria-expanded')).toBe('false');
    expect(toggle.getAttribute('aria-label')).toBe('Expand Library');
    toggle.click();
    await settle();
    expect(toggle.getAttribute('aria-expanded')).toBe('true');
    expect(toggle.getAttribute('aria-label')).toBe('Collapse Library');
    const controlled = element.querySelector(`#${toggle.getAttribute('aria-controls')}`);
    expect(controlled?.tagName.toLowerCase()).toBe('disc-collection-tree');
    expect(controlled?.querySelector('[role="status"]')?.textContent?.trim()).toBe('Loading…');

    search('communities/library').flush(answer([entry('communities/library/rare', 'Rare books', 'COLLECTION')]));
    await settle();
    expect(controlled?.querySelector('.disc-tree-link')?.textContent).toBe('Rare books');

    // a collection without communities or collections says so
    controlled!.querySelector<HTMLButtonElement>('.disc-tree-toggle')!.click();
    await settle();
    search('communities/library/rare').flush(answer([]));
    await settle();
    expect(element.textContent).toContain('No communities or collections inside.');

    // collapsing keeps what was loaded: expanding again asks nothing
    toggle.click();
    await settle();
    expect(element.querySelector(`#${CSS.escape(controlled!.id)}`)).toBeNull();
    toggle.click();
    await settle();
    http.expectNone((r) => r.params.get('within') === 'communities/library');

    element.querySelector<HTMLButtonElement>('.disc-tree-more')!.click();
    await settle();
    const more = search('communities');
    expect(more.request.params.get('page')).toBe('2');
    more.flush(answer([entry('communities/zines', 'Zines', 'COLLECTION')], 51, 2));
    await settle();
    const topLevel = Array.from(element.querySelectorAll('ul.disc-tree-root > li > .disc-tree-row .disc-tree-link'));
    expect(topLevel.map((a) => a.textContent)).toEqual(['Library', 'Maps', 'Zines']);

    const document = TestBed.inject(DOCUMENT);
    expect(document.title).toBe('Communities and collections | Articulorium');
    expect(document.head.querySelector('link[rel="canonical"]')?.getAttribute('href')).toBe('http://localhost:8000/discover/en/collections');
    http.verify();
  });

  it('offers to try again when a level cannot be loaded, answering 503 for the top level', async () => {
    await configure();
    await harness.navigateByUrl('/en/collections');
    await settle();
    search('communities').flush({ status: 503 }, { status: 503, statusText: 'Unavailable' });
    await settle();

    const element = harness.routeNativeElement as HTMLElement;
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('This list cannot be loaded right now.');
    expect(response.status).toBe(503);
    element.querySelector<HTMLButtonElement>('[role="alert"] button')!.click();
    await settle();
    search('communities').flush(answer([entry('communities/library', 'Library', 'COMMUNITY')]));
    await settle();
    expect(element.querySelector('.disc-tree-link')?.textContent).toBe('Library');
  });

  it('does not exist when switched off', async () => {
    await configure(false);
    await harness.navigateByUrl('/en/collections');
    await settle();

    http.expectNone(() => true);
    expect((harness.routeNativeElement as HTMLElement).querySelector('h1')?.textContent).toBe('Not found');
    expect(response.status).toBe(404);
  });
});
