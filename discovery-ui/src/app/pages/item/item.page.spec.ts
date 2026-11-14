import { DOCUMENT } from '@angular/common';
import { HttpTestingController } from '@angular/common/http/testing';
import { RESPONSE_INIT } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter, withComponentInputBinding } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { setUpDiscovery, testProviders } from '../../../testing/providers';
import { itemMatcher } from '../../app.routes';
import { ItemView } from '../../core/api/discovery-api';
import { ItemPage } from './item.page';

const ITEM: ItemView = {
  path: 'c/rare',
  iri: 'http://localhost:8000/platform/c/rare',
  title: 'Rare books',
  description: 'Precious things',
  kind: 'COLLECTION',
  types: ['https://articulorium.org/ns/repository#Collection'],
  languages: [],
  fields: [
    { field: 'creator', values: ['Library staff'], link: false },
    { field: 'license', values: ['https://creativecommons.org/licenses/by/4.0/'], link: true },
  ],
  trail: [{ path: 'c', title: 'Library', kind: 'COMMUNITY' }],
  thumbnail: 'http://x/t.jpg',
};

describe('ItemPage', () => {
  let harness: RouterTestingHarness;
  let http: HttpTestingController;
  const response: { status?: number } = {};

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: testProviders(
        provideRouter([{ path: ':lang', children: [{ matcher: itemMatcher, component: ItemPage }] }], withComponentInputBinding()),
        { provide: RESPONSE_INIT, useValue: response },
      ),
    });
    await setUpDiscovery('en');
    http = TestBed.inject(HttpTestingController);
    harness = await RouterTestingHarness.create();
  });

  const tick = () => new Promise((resolve) => setTimeout(resolve));

  it('presents a collection with its trail, metadata, links and members', async () => {
    const navigation = harness.navigateByUrl('/en/items/c/rare');
    await tick();
    const request = http.expectOne((r) => r.url === 'http://test/discover/api/item');
    expect(request.request.params.get('path')).toBe('c/rare');
    request.flush(ITEM);
    await navigation;
    await tick();
    harness.fixture.detectChanges();
    await tick();
    const members = http.expectOne((r) => r.url === 'http://test/discover/api/search');
    expect(members.request.params.get('within')).toBe('c/rare');
    members.flush({ query: '', page: 1, size: 24, total: 1, results: [{ path: 'c/rare/moby', title: 'Moby Dick', kind: 'OBJECT', types: [] }], hidden: 0, facets: [], filters: [] });
    await harness.fixture.whenStable();

    const element = harness.routeNativeElement as HTMLElement;
    expect(element.querySelector('h1')?.textContent).toBe('Rare books');
    const crumbs = Array.from(element.querySelectorAll('.disc-breadcrumb li')).map((li) => li.textContent?.trim());
    expect(crumbs).toEqual(['Home', 'Library', 'Rare books']);
    expect(element.querySelector('.disc-breadcrumb [aria-current="page"]')?.textContent).toBe('Rare books');
    const fields = Array.from(element.querySelectorAll('.disc-field')).map(
      (f) => `${f.querySelector('dt')?.textContent?.trim()}: ${f.querySelector('dd')?.textContent?.trim()}`,
    );
    expect(fields).toEqual(['Creator: Library staff', 'License: https://creativecommons.org/licenses/by/4.0/']);
    expect(element.querySelector('.disc-field dd a')?.getAttribute('rel')).toBe('noopener');
    expect(element.querySelector('.disc-item-thumbnail')?.getAttribute('alt')).toBe('Preview of Rare books');
    expect(element.querySelector('#disc-members-heading')?.textContent).toBe('In this collection');
    expect(element.querySelector('disc-resource-card a')?.getAttribute('href')).toBe('/en/items/c/rare/moby');

    const document = TestBed.inject(DOCUMENT);
    expect(document.title).toBe('Rare books | Articulorium');
    expect(document.getElementById('disc-structured-data')?.textContent).toContain('"@type":"Collection"');
  });

  it('pages through the members of a collection', async () => {
    const members = (page: number, results: string[]) => ({
      query: '',
      page,
      size: 24,
      total: 30,
      results: results.map((path) => ({ path, title: path, kind: 'OBJECT' as const, types: [] })),
      hidden: 0,
      facets: [],
      filters: [],
    });
    const navigation = harness.navigateByUrl('/en/items/c/rare?page=2');
    await tick();
    http.expectOne((r) => r.url === 'http://test/discover/api/item').flush(ITEM);
    await navigation;
    await tick();
    harness.fixture.detectChanges();
    await tick();
    const second = http.expectOne((r) => r.url === 'http://test/discover/api/search');
    expect(second.request.params.get('page')).toBe('2');
    expect(second.request.params.get('size')).toBe('24');
    second.flush(members(2, ['c/rare/x']));
    await harness.fixture.whenStable();

    const element = harness.routeNativeElement as HTMLElement;
    const pager = element.querySelector('#disc-members nav');
    expect(pager?.getAttribute('aria-label')).toBe('Pages of members');
    expect(pager?.querySelector('.disc-page-of')?.textContent).toBe('Page 2 of 2');
    expect(pager?.querySelector('a[rel="prev"]')?.getAttribute('href')).toBe('/en/items/c/rare#disc-members');
    expect(pager?.querySelector('a[rel="next"]')).toBeNull();

    // another page loads the members only, and the listing stays while it does
    await harness.navigateByUrl('/en/items/c/rare');
    await tick();
    http.expectNone((r) => r.url === 'http://test/discover/api/item');
    const first = http.expectOne((r) => r.url === 'http://test/discover/api/search');
    expect(first.request.params.get('page')).toBe('1');
    harness.fixture.detectChanges();
    expect(element.querySelector('#disc-members')?.getAttribute('aria-busy')).toBe('true');
    expect(element.querySelector('#disc-members disc-resource-card a')?.textContent).toBe('c/rare/x');
    first.flush(members(1, ['c/rare/a', 'c/rare/b']));
    await harness.fixture.whenStable();
    expect(element.querySelectorAll('#disc-members disc-resource-card')).toHaveLength(2);
    expect(element.querySelector('#disc-members a[rel="next"]')?.getAttribute('href')).toBe('/en/items/c/rare?page=2#disc-members');
  });

  it('answers missing and private resources with 404', async () => {
    const navigation = harness.navigateByUrl('/en/items/c/secret');
    await tick();
    http.expectOne((r) => r.url === 'http://test/discover/api/item').flush({ status: 404 }, { status: 404, statusText: 'Not Found' });
    await navigation;
    await harness.fixture.whenStable();

    const element = harness.routeNativeElement as HTMLElement;
    expect(element.querySelector('h1')?.textContent).toBe('Not found');
    expect(response.status).toBe(404);
    expect(TestBed.inject(DOCUMENT).head.querySelector('meta[name="robots"]')?.getAttribute('content')).toBe('noindex, follow');
  });
});
