import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideRouter } from '@angular/router';

import { TopicModel } from '../core/ai/ai';
import { ResourceView } from '../core/ldp/models';
import { Graph, subject } from '../core/rdf/graph';
import { parseWacAllow } from '../core/webac/webac';
import { aiCapabilities } from '../testing/test-ai';
import { TEST_AI, TEST_BASE, provideTestConfig } from '../testing/test-config';
import { provideTestI18n } from '../testing/test-i18n';
import { TopicSuggestionComponent } from './topic-suggestion.component';

const URL = `${TEST_BASE}communities/lib`;

const view = (wac: string): ResourceView => ({
  path: 'communities/lib',
  subject: subject(URL),
  url: URL,
  graph: new Graph([]),
  allow: new Set(['GET', 'PATCH']),
  linkTypes: [],
  wacAllow: parseWacAllow(wac),
});

const MODEL: TopicModel = {
  path: 'communities/lib',
  summary: 'Books and letters about the sea.',
  topics: [
    { label: 'Whaling', keywords: ['whale', 'ship'], description: 'Hunting whales.', members: [{ path: 'communities/lib/c/moby', title: 'Moby Dick' }], share: 0.6 },
    { label: 'Navigation', keywords: [], description: '', members: [], share: 0.4 },
  ],
  analysed: 12,
  truncated: true,
  language: 'en',
};

describe('TopicSuggestionComponent', () => {
  let fixture: ComponentFixture<TopicSuggestionComponent>;
  let http: HttpTestingController;
  const snackBar = { open: vi.fn() };

  const render = (wac = 'user="read write"'): HTMLElement => {
    fixture = TestBed.createComponent(TopicSuggestionComponent);
    fixture.componentRef.setInput('view', view(wac));
    fixture.detectChanges();
    http.expectOne(`${TEST_AI}capabilities`).flush(aiCapabilities(['topics']));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  const click = (element: HTMLElement, selector: string): void => {
    (element.querySelector(selector) as HTMLElement).click();
    fixture.detectChanges();
  };

  beforeEach(() => {
    snackBar.open.mockReset();
    TestBed.configureTestingModule({
      imports: [TopicSuggestionComponent],
      providers: [
        ...provideTestI18n(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTestConfig(),
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('is offered only to users who may change the community or collection', () => {
    expect(render('user="read"').querySelector('.ui-topic-suggestion')).toBeNull();
  });

  it('shows topics and summary for review and adds the ones kept', () => {
    const element = render();
    click(element, '.ui-ai-suggest');
    const request = http.expectOne({ method: 'POST', url: `${TEST_AI}topic-models` });
    expect(request.request.body).toEqual({ path: 'communities/lib' });
    request.flush(MODEL);
    fixture.detectChanges();

    expect(element.textContent).toContain('Based on 12 items.');
    expect(element.textContent).toContain('There are more items than the agent read.');
    expect(element.querySelector('.ui-ai-summary')?.textContent).toContain('Books and letters about the sea.');
    expect(element.querySelector('.ui-ai-summary')?.getAttribute('lang')).toBe('en');
    expect([...element.querySelectorAll('.ui-ai-topics > li strong')].map((s) => s.textContent)).toEqual(['Whaling', 'Navigation']);
    expect(element.querySelector('.ui-ai-topics details summary')?.textContent).toContain('1 item');

    // drop "Navigation"
    click(element, '.ui-ai-topics > li:nth-child(2) input[type="checkbox"]');
    click(element, '.ui-ai-apply');
    const patch = http.expectOne({ method: 'PATCH', url: URL });
    expect(patch.request.body).toContain(`DELETE WHERE { <${URL}> <http://purl.org/dc/terms/abstract> ?o }`);
    expect(patch.request.body).toContain(`<${URL}> <http://purl.org/dc/terms/subject> "Whaling"@en .`);
    expect(patch.request.body).not.toContain('Navigation');
    expect(patch.request.body).toContain('"Books and letters about the sea."@en');
    patch.flush('');
    fixture.detectChanges();
    expect(snackBar.open.mock.calls[0][0]).toBe('Added 1 topic');
  });

  it('can keep only the summary', () => {
    const element = render();
    click(element, '.ui-ai-suggest');
    http.expectOne(`${TEST_AI}topic-models`).flush(MODEL);
    fixture.detectChanges();
    click(element, '.ui-ai-topics > li:nth-child(1) input[type="checkbox"]');
    click(element, '.ui-ai-topics > li:nth-child(2) input[type="checkbox"]');
    click(element, '.ui-ai-apply');
    const patch = http.expectOne({ method: 'PATCH', url: URL });
    expect(patch.request.body).not.toContain('subject');
    patch.flush('');
    fixture.detectChanges();
    expect(snackBar.open.mock.calls[0][0]).toBe('Added the summary');
  });

  it('says when there are no items, and explains failures', () => {
    const element = render();
    click(element, '.ui-ai-suggest');
    http.expectOne(`${TEST_AI}topic-models`).flush({ ...MODEL, analysed: 0, topics: [], summary: '' });
    fixture.detectChanges();
    expect(element.querySelector('.ui-ai-nothing')?.textContent).toContain('There are no items here to analyse.');
    expect(element.querySelector('.ui-ai-apply')).toBeNull();

    click(element, 'button[mat-button]');
    click(element, '.ui-ai-suggest');
    http
      .expectOne(`${TEST_AI}topic-models`)
      .flush({ reason: 'MODEL_UNAVAILABLE', detail: 'no model' }, { status: 503, statusText: 'Service Unavailable' });
    fixture.detectChanges();
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('No AI model is available.');
  });
});
