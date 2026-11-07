import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { Router, provideRouter } from '@angular/router';

import { TopologySuggestion } from '../core/ai/ai';
import { TopologyDraftStore } from '../core/ai/ai.service';
import { aiCapabilities } from '../testing/test-ai';
import { TEST_AI, provideTestConfig } from '../testing/test-config';
import { provideTestI18n } from '../testing/test-i18n';
import { TopologySuggestionDialogComponent } from './topology-suggestion-dialog.component';

describe('TopologySuggestionDialogComponent', () => {
  let fixture: ComponentFixture<TopologySuggestionDialogComponent>;
  let http: HttpTestingController;
  let router: Router;
  const dialogRef = { close: vi.fn() };

  const open = async (data: unknown = null): Promise<HTMLElement> => {
    TestBed.configureTestingModule({
      imports: [TopologySuggestionDialogComponent],
      providers: [
        ...provideTestI18n(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        provideRouter([{ path: '**', children: [] }]),
        provideTestConfig(),
        { provide: MatDialogRef, useValue: dialogRef },
        { provide: MAT_DIALOG_DATA, useValue: data },
      ],
    });
    http = TestBed.inject(HttpTestingController);
    router = TestBed.inject(Router);
    vi.spyOn(router, 'navigate').mockResolvedValue(true);
    fixture = TestBed.createComponent(TopologySuggestionDialogComponent);
    fixture.detectChanges();
    // the dialog only opens when the agent offers topology suggestions
    http.expectOne(`${TEST_AI}capabilities`).flush(aiCapabilities(['topology']));
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  const type = async (element: HTMLElement, selector: string, value: string): Promise<void> => {
    const input = element.querySelector(selector) as HTMLInputElement | HTMLTextAreaElement;
    input.value = value;
    input.dispatchEvent(new Event('input'));
    fixture.detectChanges();
    await fixture.whenStable();
    fixture.detectChanges();
  };

  const submit = (element: HTMLElement): void => {
    (element.querySelector('.ui-ai-suggest') as HTMLButtonElement).click();
    fixture.detectChanges();
  };

  beforeEach(() => dialogRef.close.mockReset());

  afterEach(() => http.verify());

  it('asks only once something is described, and opens a recommended existing topology', async () => {
    const element = await open({ context: 'communities/lib/letters' });
    expect((element.querySelector('.ui-ai-suggest') as HTMLButtonElement).disabled).toBe(true);
    await type(element, 'textarea', '  Letters with page scans  ');
    await type(element, 'input[name="sample"]', '/collections/letters/one/');
    submit(element);

    const request = http.expectOne({ method: 'POST', url: `${TEST_AI}topology-suggestions` });
    expect(request.request.body).toEqual({
      description: 'Letters with page scans',
      sample: 'collections/letters/one',
      context: 'communities/lib/letters',
    });
    const suggestion: TopologySuggestion = {
      recommendation: 'EXISTING',
      existing: { id: 'manuscript', name: 'Manuscript', builtIn: true } as TopologySuggestion['existing'],
      errors: [],
      rationale: 'Letters are manuscripts.',
      confidence: 0.82,
    };
    request.flush(suggestion);
    fixture.detectChanges();

    expect(element.querySelector('.ui-ai-result h3')?.textContent).toContain('Use the existing topology “Manuscript”');
    expect(element.textContent).toContain('Confidence 82 %');
    expect(element.textContent).toContain('Letters are manuscripts.');
    (element.querySelector('.ui-ai-open') as HTMLButtonElement).click();
    expect(dialogRef.close).toHaveBeenCalled();
    expect(router.navigate).toHaveBeenCalledWith(['/topologies', 'manuscript']);
  });

  it('hands a draft to the topology editor, with what saving it would still refuse', async () => {
    const element = await open({ description: 'Postcards with a front and a back' });
    submit(element);
    http.expectOne(`${TEST_AI}topology-suggestions`).flush({
      recommendation: 'NEW',
      draft: { id: 'postcard-set', name: 'Postcard set', root: { key: 'set', label: 'Set' } },
      errors: ['The root node needs at least one file or child.'],
      rationale: null,
      confidence: 0.55,
    } satisfies TopologySuggestion);
    fixture.detectChanges();

    expect(element.querySelector('.ui-ai-result h3')?.textContent).toContain('A new topology: “Postcard set”');
    expect(element.querySelector('.ui-ai-result ul.ui-error-text')?.textContent).toContain('needs at least one file');
    (element.querySelector('.ui-ai-open') as HTMLButtonElement).click();

    const draft = TestBed.inject(TopologyDraftStore).take();
    expect(draft).toMatchObject({ id: 'postcard-set', name: 'Postcard set', builtIn: false, root: { key: 'set', files: [], children: [] } });
    expect(router.navigate).toHaveBeenCalledWith(['/topologies', 'new'], { queryParams: { origin: 'ai' } });
  });

  it('explains failures of the agent', async () => {
    const element = await open({ description: 'Anything' });
    submit(element);
    http
      .expectOne(`${TEST_AI}topology-suggestions`)
      .flush({ reason: 'INVALID_OUTPUT', detail: 'unusable' }, { status: 502, statusText: 'Bad Gateway' });
    fixture.detectChanges();
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('could not be used');
    expect(element.querySelector('.ui-ai-open')).toBeNull();
  });
});
