import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';

import { MediaMetadataSuggestion } from '../core/ai/ai';
import { ResourceView } from '../core/ldp/models';
import { Graph, subject } from '../core/rdf/graph';
import { parseWacAllow } from '../core/webac/webac';
import { aiCapabilities } from '../testing/test-ai';
import { TEST_AI, TEST_BASE, provideTestConfig } from '../testing/test-config';
import { provideTestI18n } from '../testing/test-i18n';
import { MediaSuggestionComponent } from './media-suggestion.component';

const URL = `${TEST_BASE}books/cover.png`;

const view = (mimeType: string): ResourceView => ({
  path: 'books/cover.png',
  subject: subject(URL),
  url: URL,
  graph: new Graph([]),
  binary: { url: URL, descriptionUrl: `${URL}/fcr:metadata`, mimeType },
  allow: new Set(['GET', 'PATCH']),
  linkTypes: [],
  wacAllow: parseWacAllow('user="read write"'),
});

const SUGGESTION: MediaMetadataSuggestion = {
  path: 'books/cover.png',
  description: 'books/cover.png/fcr:metadata',
  mediaType: 'image/png',
  kind: 'IMAGE',
  imageShown: false,
  statements: [
    { predicate: 'http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#width', value: '800', kind: 'LITERAL', datatype: 'http://www.w3.org/2001/XMLSchema#integer', origin: 'FILE' },
    { predicate: 'http://purl.org/dc/terms/title', value: 'A whale', kind: 'LITERAL', language: 'en', origin: 'MODEL' },
    { predicate: 'http://purl.org/dc/terms/subject', value: 'Whaling', kind: 'LITERAL', language: 'en', origin: 'MODEL' },
  ],
  notes: ['IMAGE_TOO_LARGE_TO_SHOW', 'SOMETHING_UNKNOWN'],
};

describe('MediaSuggestionComponent', () => {
  let fixture: ComponentFixture<MediaSuggestionComponent>;
  let http: HttpTestingController;
  const snackBar = { open: vi.fn() };

  const render = (mimeType: string, offered = true): HTMLElement => {
    fixture = TestBed.createComponent(MediaSuggestionComponent);
    fixture.componentRef.setInput('view', view(mimeType));
    fixture.detectChanges();
    http.expectOne(`${TEST_AI}capabilities`).flush(aiCapabilities(offered ? ['media'] : ['topology']));
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
      imports: [MediaSuggestionComponent],
      providers: [...provideTestI18n(), provideHttpClient(), provideHttpClientTesting(), provideTestConfig(), { provide: MatSnackBar, useValue: snackBar }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('is hidden when the agent does not describe media, or the binary is not one it describes', () => {
    expect(render('image/png', false).querySelector('.ui-media-suggestion')).toBeNull();
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({
      imports: [MediaSuggestionComponent],
      providers: [...provideTestI18n(), provideHttpClient(), provideHttpClientTesting(), provideTestConfig()],
    });
    http = TestBed.inject(HttpTestingController);
    expect(render('text/csv').querySelector('.ui-media-suggestion')).toBeNull();
  });

  it('shows the suggestion for review and adds only the statements kept to the description', () => {
    const element = render('image/png');
    click(element, '.ui-ai-suggest');
    expect(element.textContent).toContain('Asking the AI agent');
    const request = http.expectOne({ method: 'POST', url: `${TEST_AI}media-metadata` });
    expect(request.request.body).toEqual({ path: 'books/cover.png' });
    request.flush(SUGGESTION);
    fixture.detectChanges();

    const items = [...element.querySelectorAll('.ui-ai-statements li')].map((li) => li.textContent?.replace(/\s+/g, ' ').trim());
    expect(items[0]).toContain('Width');
    expect(items[0]).toContain('From the file');
    expect(items[1]).toContain('Title');
    expect(items[1]).toContain('Suggested');
    // only notes the user interface knows are explained
    expect(element.querySelectorAll('.ui-ai-notes li')).toHaveLength(1);
    expect(element.querySelector('.ui-ai-apply')?.textContent).toContain('Add 3 statements');

    // drop the subject
    click(element, '.ui-ai-statements li:nth-child(3) input[type="checkbox"]');
    expect(element.querySelector('.ui-ai-apply')?.textContent).toContain('Add 2 statements');
    click(element, '.ui-ai-apply');

    const patch = http.expectOne({ method: 'PATCH', url: `${URL}/fcr:metadata` });
    expect(patch.request.body).toBe(
      `INSERT DATA { <${URL}> <http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#width> "800"^^<http://www.w3.org/2001/XMLSchema#integer> . ` +
        `<${URL}> <http://purl.org/dc/terms/title> "A whale"@en . }`,
    );
    let applied = false;
    fixture.componentInstance.applied.subscribe(() => (applied = true));
    patch.flush('');
    fixture.detectChanges();
    expect(applied).toBe(true);
    expect(snackBar.open.mock.calls[0][0]).toBe('Added 2 statements');
    // the suggestion is gone, the user can ask again
    expect(element.querySelector('.ui-ai-statements')).toBeNull();
    expect(element.querySelector('.ui-ai-suggest')).not.toBeNull();
  });

  it('explains why the agent could not help, and saves nothing', () => {
    const element = render('application/pdf');
    click(element, '.ui-ai-suggest');
    http
      .expectOne(`${TEST_AI}media-metadata`)
      .flush({ reason: 'TOO_LARGE', detail: 'too large' }, { status: 413, statusText: 'Payload Too Large' });
    fixture.detectChanges();
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('This file is too large for the AI agent.');
    expect(element.querySelector('.ui-ai-apply')).toBeNull();
  });

  it('says when there is nothing to suggest', () => {
    const element = render('video/mp4');
    click(element, '.ui-ai-suggest');
    http.expectOne(`${TEST_AI}media-metadata`).flush({ ...SUGGESTION, kind: 'VIDEO', statements: [], notes: [] });
    fixture.detectChanges();
    expect(element.querySelector('.ui-ai-nothing')?.textContent).toContain('found nothing to suggest');
    expect(element.querySelector('.ui-ai-apply')).toBeNull();
  });
});
