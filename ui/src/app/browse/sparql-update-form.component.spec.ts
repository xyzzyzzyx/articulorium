import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';

import { Graph } from '../core/rdf/graph';
import { parseNTriples } from '../core/rdf/ntriples';
import { TEST_BASE, provideTestConfig } from '../testing/test-config';
import { SparqlUpdateFormComponent } from './sparql-update-form.component';

describe('SparqlUpdateFormComponent', () => {
  const target = `${TEST_BASE}books`;
  const graph = new Graph(parseNTriples(`<${target}> <http://purl.org/dc/terms/title> "Books" .\n`));

  let fixture: ComponentFixture<SparqlUpdateFormComponent>;
  let http: HttpTestingController;

  /** The editor's text area. */
  const editor = (): HTMLTextAreaElement => (fixture.nativeElement as HTMLElement).querySelector('textarea')!;

  /** Types a request into the editor. */
  const type = async (text: string): Promise<void> => {
    editor().value = text;
    editor().dispatchEvent(new Event('input'));
    await fixture.whenStable();
  };

  /** Presses a button of the form. */
  const press = (label: string): void => {
    const buttons = [...(fixture.nativeElement as HTMLElement).querySelectorAll('button')];
    buttons.find((button) => button.textContent?.trim() === label)!.click();
  };

  beforeEach(async () => {
    TestBed.configureTestingModule({
      imports: [SparqlUpdateFormComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideTestConfig()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(SparqlUpdateFormComponent);
    fixture.componentRef.setInput('target', target);
    fixture.componentRef.setInput('graph', graph);
    fixture.componentRef.setInput('etag', 'W/"1"');
    await fixture.whenStable();
  });

  afterEach(() => http.verify());

  it('starts from the prefixes the resource uses', () => {
    expect(editor().value).toBe('PREFIX dcterms: <http://purl.org/dc/terms/>\n\nDELETE { }\nINSERT { }\nWHERE { }\n');
  });

  it('sends the update with If-Match and resets the editor afterwards', async () => {
    const updated = vi.fn();
    fixture.componentInstance.updated.subscribe(updated);
    const update = 'INSERT { <> <http://purl.org/dc/terms/subject> "whales" } WHERE { }';
    await type(update);

    press('Update');
    const request = http.expectOne({ method: 'PATCH', url: target });
    expect(request.request.body).toBe(update);
    expect(request.request.headers.get('If-Match')).toBe('W/"1"');
    request.flush('', { status: 204, statusText: 'No Content' });
    await fixture.whenStable();

    expect(updated).toHaveBeenCalledOnce();
    // the prefixes did not change, so only the explicit reset clears the request
    expect(editor().value).toContain('DELETE { }');
  });

  it('keeps the request when the update fails', async () => {
    await type('not sparql');
    press('Update');
    http.expectOne({ method: 'PATCH', url: target }).flush('bad', { status: 400, statusText: 'Bad Request' });
    await fixture.whenStable();

    expect(editor().value).toBe('not sparql');
  });

  it('resets the editor on request', async () => {
    await type('garbage');
    press('Reset');
    await fixture.whenStable();

    expect(editor().value).toContain('WHERE { }');
  });
});
