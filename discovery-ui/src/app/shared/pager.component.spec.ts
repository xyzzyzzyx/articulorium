import { Component, signal } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';
import { RouterTestingHarness } from '@angular/router/testing';

import { setUpDiscovery, testProviders } from '../../testing/providers';
import { PagerComponent, pageCount, parsePage } from './pager.component';

@Component({
  imports: [PagerComponent],
  template: `<disc-pager [page]="page()" [pages]="pages()" label="Pages of members" fragment="members" />`,
})
class HostComponent {
  readonly page = signal(2);

  readonly pages = signal(3);
}

describe('pageCount', () => {
  it('counts partial pages and at least one page', () => {
    expect(pageCount(0, 24)).toBe(1);
    expect(pageCount(24, 24)).toBe(1);
    expect(pageCount(25, 24)).toBe(2);
    expect(pageCount(10, 0)).toBe(1);
  });
});

describe('parsePage', () => {
  it('reads positive integers and falls back to the first page', () => {
    expect(parsePage('3')).toBe(3);
    expect(parsePage(4)).toBe(4);
    expect(parsePage('0')).toBe(1);
    expect(parsePage('-2')).toBe(1);
    expect(parsePage('two')).toBe(1);
    expect(parsePage(undefined)).toBe(1);
    expect(parsePage(['2'])).toBe(1);
  });
});

describe('PagerComponent', () => {
  let harness: RouterTestingHarness;

  beforeEach(async () => {
    TestBed.configureTestingModule({
      providers: testProviders(provideRouter([{ path: ':lang/items/c', component: HostComponent }])),
    });
    await setUpDiscovery('en');
    harness = await RouterTestingHarness.create();
  });

  it('links to the neighbouring pages, keeping the other query parameters', async () => {
    const host = await harness.navigateByUrl('/en/items/c?page=2&lang=x', HostComponent);
    await harness.fixture.whenStable();
    const element = harness.routeNativeElement as HTMLElement;

    const nav = element.querySelector('nav');
    expect(nav?.getAttribute('aria-label')).toBe('Pages of members');
    expect(element.querySelector('.disc-page-of')?.textContent).toBe('Page 2 of 3');
    // the first page has no page parameter, so it has one URL
    expect(element.querySelector('a[rel="prev"]')?.getAttribute('href')).toBe('/en/items/c?lang=x#members');
    expect(element.querySelector('a[rel="next"]')?.getAttribute('href')).toBe('/en/items/c?page=3&lang=x#members');

    host.page.set(3);
    await harness.fixture.whenStable();
    expect(element.querySelector('a[rel="next"]')).toBeNull();

    host.pages.set(1);
    await harness.fixture.whenStable();
    expect(element.querySelector('nav')).toBeNull();
  });
});
