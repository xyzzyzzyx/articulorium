import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ART, LDP, RDF } from '../core/rdf/vocabulary';
import { TEST_BASE, provideTestConfig } from '../testing/test-config';
import { CommunitiesPageComponent } from './communities-page.component';

const T = 'http://purl.org/dc/terms/title';

describe('CommunitiesPageComponent', () => {
  let fixture: ComponentFixture<CommunitiesPageComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [CommunitiesPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([]), provideTestConfig()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(CommunitiesPageComponent);
    fixture.componentRef.setInput('path', '');
    fixture.detectChanges();
    TestBed.tick();
  });

  afterEach(() => http.verify());

  it('lists the communities and creates new ones', async () => {
    http.expectOne(`${TEST_BASE}communities`).flush(
      `<${TEST_BASE}communities> <${RDF.type}> <${ART.communityRoot}> .\n` +
        `<${TEST_BASE}communities> <${RDF.type}> <${LDP.basicContainer}> .\n` +
        `<${TEST_BASE}communities> <${LDP.contains}> <${TEST_BASE}communities/lib> .\n`,
      { headers: { 'WAC-Allow': 'user="read write append",public="read"' } },
    );
    TestBed.tick();
    http.expectOne(`${TEST_BASE}communities/lib`).flush(
      `<${TEST_BASE}communities/lib> <${RDF.type}> <${ART.community}> .\n<${TEST_BASE}communities/lib> <${T}> "Libraries" .\n`,
    );
    await fixture.whenStable();
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect(element.querySelector('h1')?.textContent).toBe('Communities');
    const link = element.querySelector('.ui-communities-list a');
    expect(link?.textContent).toBe('Libraries');
    expect(link?.getAttribute('href')).toBe('/communities/lib');
    expect(element.querySelector('.ui-new-community')).not.toBeNull();
    expect(element.querySelector('.ui-new-collection')).toBeNull();

    const component = fixture.componentInstance as unknown as { communityTitle: string };
    component.communityTitle = 'Science & Engineering';
    (element.querySelector('.ui-new-community') as HTMLFormElement).dispatchEvent(new Event('submit'));
    const request = http.expectOne({ method: 'POST', url: `${TEST_BASE}communities` });
    expect(request.request.headers.get('Slug')).toBe('science-engineering');
    expect(request.request.body).toContain(`<${ART.community}>`);
    request.flush('', { status: 201, statusText: 'Created', headers: { Location: `${TEST_BASE}communities/science-engineering` } });
    TestBed.tick();
    http.expectOne(`${TEST_BASE}communities`).flush(`<${TEST_BASE}communities> <${RDF.type}> <${ART.communityRoot}> .\n`);
  });
});
