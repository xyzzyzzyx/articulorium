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
