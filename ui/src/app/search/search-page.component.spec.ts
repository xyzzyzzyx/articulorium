import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ActivatedRoute, Router, provideRouter } from '@angular/router';
import { BehaviorSubject } from 'rxjs';

import { TEST_BASE, provideTestConfig } from '../testing/test-config';
import { SearchPageComponent } from './search-page.component';

describe('SearchPageComponent', () => {
  const queryParams = new BehaviorSubject<Record<string, unknown>>({ condition: 'mime_type=image/*', max_results: '20' });

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [SearchPageComponent],
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTestConfig(),
        { provide: ActivatedRoute, useValue: { queryParams } },
      ],
    });
  });

  it('runs the search in the URL and lists the results', async () => {
    const fixture = TestBed.createComponent(SearchPageComponent);
    fixture.detectChanges();
    TestBed.tick();
    const http = TestBed.inject(HttpTestingController);
    const request = http.expectOne((req) => req.url === `${TEST_BASE}fcr:search`);
    expect(request.request.params.getAll('condition')).toEqual(['mime_type=image/*']);
    request.flush({
      items: [{ fedora_id: `${TEST_BASE}books/cover.png`, mime_type: 'image/png', content_size: 2048, rdf_type: ['http://www.w3.org/ns/ldp#NonRDFSource'] }],
      pagination: { maxResults: 20, offset: 0, totalResults: 1, pageNumber: 0, numberOfPages: 1 },
    });
    await fixture.whenStable();
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.textContent).toContain('1 result(s)');
    expect(element.querySelector('a[href="/browse/books/cover.png"]')).not.toBeNull();
    expect(element.textContent).toContain('ldp:NonRDFSource');
    expect((element.querySelector('input[name="value0"]') as HTMLInputElement).value).toBe('image/*');
    http.verify();
  });

  it('puts submitted searches in the URL', async () => {
    const router = TestBed.inject(Router);
    const navigate = vi.spyOn(router, 'navigate').mockResolvedValue(true);
    const fixture = TestBed.createComponent(SearchPageComponent);
    fixture.detectChanges();
    TestBed.tick();
    TestBed.inject(HttpTestingController)
      .match(() => true)
      .forEach((request) =>
        request.flush({ items: [], pagination: { maxResults: 20, offset: 0, totalResults: 0, pageNumber: 0, numberOfPages: 0 } }),
      );
    fixture.detectChanges();
    await fixture.whenStable();

    (fixture.nativeElement as HTMLElement).querySelector('form')?.dispatchEvent(new Event('submit'));

    expect(navigate).toHaveBeenCalledWith(['/search'], {
      queryParams: { condition: ['mime_type=image/*'], max_results: 20, offset: 0 },
    });
  });
});
