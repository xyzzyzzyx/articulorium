import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, TestRequest, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { Classification, ClassificationQueue } from '../core/harvest/harvest';
import { TEST_BASE, TEST_HARVEST, provideTestConfig } from '../testing/test-config';
import { provideTestI18n } from '../testing/test-i18n';
import { ReviewQueueComponent } from './review-queue.component';

const QUEUE = `${TEST_HARVEST}classifications`;

const PENDING: Classification = {
  source: 'repo',
  record: 'harvest/repo/rec-1',
  identifier: 'oai:repo:1',
  title: 'Moby Dick',
  status: 'PENDING',
  suggestion: {
    placement: 'EXISTING_COLLECTION',
    collection: { path: 'communities/lib/novels', title: 'Novels', trail: 'Library › Novels' },
    topology: { kind: 'EXISTING', id: 'book', name: 'Book' },
    confidence: 0.91,
    rationale: 'A novel.',
    alternatives: [{ collection: { path: 'communities/lib/sea', title: 'Sea', trail: 'Library › Sea' }, confidence: 0.4 }],
  },
  uncertain: false,
  classifiedAt: '2026-11-06T10:00:00Z',
};

const PROPOSED: Classification = {
  source: 'repo',
  record: 'harvest/repo/rec-2',
  identifier: 'oai:repo:2',
  title: 'Ship logs',
  status: 'PENDING',
  suggestion: {
    placement: 'NEW_COLLECTION',
    community: { path: 'communities/lib', title: 'Library', trail: 'Library' },
    newCollection: { title: 'Ship logs', slug: 'ship-logs', description: 'Logbooks of ships.' },
    topology: { kind: 'NONE' },
    confidence: 0.35,
  },
  uncertain: true,
  classifiedAt: '2026-11-06T10:01:00Z',
};

const FAILED: Classification = {
  source: 'other',
  record: 'harvest/other/rec-3',
  identifier: 'oai:other:3',
  title: null,
  status: 'FAILED',
  failure: 'MODEL_UNAVAILABLE',
  uncertain: false,
};

const page = (classifications: Classification[], decide = true, total = classifications.length): ClassificationQueue => ({
  classifications,
  total,
  offset: 0,
  decide,
});

describe('ReviewQueueComponent', () => {
  let fixture: ComponentFixture<ReviewQueueComponent>;
  let http: HttpTestingController;
  const snackBar = { open: vi.fn() };
  const dialog = { open: vi.fn() };

  const settle = async (): Promise<HTMLElement> => {
    TestBed.tick();
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  const expectQueue = (params = 'status=open&offset=0&limit=25'): TestRequest => {
    // the queue is a resource: reloads are sent when its effect runs
    TestBed.tick();
    return http.expectOne((request) => request.method === 'GET' && request.urlWithParams === `${QUEUE}?${params}`);
  };

  const render = async (first: ClassificationQueue): Promise<HTMLElement> => {
    fixture = TestBed.createComponent(ReviewQueueComponent);
    fixture.componentRef.setInput('sources', ['repo', 'other']);
    fixture.detectChanges();
    TestBed.tick();
    expectQueue().flush(first);
    return settle();
  };

  const card = (element: HTMLElement, index: number): HTMLElement =>
    element.querySelectorAll<HTMLElement>('.ui-review-item')[index];

  beforeEach(() => {
    snackBar.open.mockReset();
    dialog.open.mockReset();
    dialog.open.mockReturnValue({ afterClosed: () => of(true) });
    TestBed.configureTestingModule({
      imports: [ReviewQueueComponent],
      providers: [
        ...provideTestI18n(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        provideRouter([]),
        provideTestConfig(),
        { provide: MatSnackBar, useValue: snackBar },
        { provide: MatDialog, useValue: dialog },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists the suggestions with where they place each record, their topology, confidence and doubts', async () => {
    const element = await render(page([PENDING, PROPOSED, FAILED]));

    const first = card(element, 0);
    expect(first.querySelector('mat-card-title')?.textContent).toContain('Moby Dick');
    expect(first.querySelector('.ui-review-placement')?.textContent).toContain('Library › Novels');
    expect(first.querySelector('.ui-review-topology')?.textContent).toContain('Book');
    expect(first.textContent).toContain('Confidence 91 %');
    expect(first.textContent).toContain('A novel.');
    expect(first.querySelector('.ui-review-accept')).not.toBeNull();
    expect(first.querySelector('.ui-review-create')).toBeNull();

    const second = card(element, 1);
    expect(second.classList).toContain('ui-review-uncertain');
    expect(second.querySelector('.ui-review-uncertain-badge')?.textContent).toContain('Uncertain');
    expect(second.querySelector('.ui-review-placement')?.textContent).toContain('A new collection “Ship logs” in Library');
    expect(second.querySelector('.ui-review-topology')?.textContent).toContain('No topology');
    // a proposed structure is created on acceptance, never accepted as it is
    expect(second.querySelector('.ui-review-accept')).toBeNull();
    expect(second.querySelector('.ui-review-create')).not.toBeNull();

    const third = card(element, 2);
    expect(third.querySelector('mat-card-title')?.textContent).toContain('oai:other:3');
    expect(third.textContent).toContain('Classification failed: No AI model is available.');
    expect(third.querySelector('.ui-review-retry')).not.toBeNull();
  });

  it('accepts a suggestion as it is and shows the queue again', async () => {
    const element = await render(page([PENDING]));
    (card(element, 0).querySelector('.ui-review-accept') as HTMLButtonElement).click();
    const accept = http.expectOne({ method: 'POST', url: `${QUEUE}/repo/rec-1/acceptance` });
    expect(accept.request.body).toEqual({});
    accept.flush({ ...PENDING, status: 'ACCEPTED', placedAt: 'communities/lib/novels/moby-dick' });
    expect(snackBar.open.mock.calls[0][0]).toBe('“Moby Dick” was placed');
    expectQueue().flush(page([]));
    const after = await settle();
    expect(after.querySelector('.ui-review-empty')?.textContent).toContain('No records are waiting for review.');
  });

  it('places a record in an alternative collection with the suggested topology', async () => {
    const element = await render(page([PENDING]));
    const useThis = [...card(element, 0).querySelectorAll<HTMLButtonElement>('.ui-review-alternatives button')];
    useThis[0].click();
    const accept = http.expectOne({ method: 'POST', url: `${QUEUE}/repo/rec-1/acceptance` });
    expect(accept.request.body).toEqual({ collection: 'communities/lib/sea', topology: 'book' });
    accept.flush({ ...PENDING, status: 'ACCEPTED' });
    expectQueue().flush(page([]));
    await settle();
  });

  it('rejects a suggestion once confirmed, and classifies records again', async () => {
    const element = await render(page([PENDING, FAILED]));
    (card(element, 0).querySelector('.ui-review-reject') as HTMLButtonElement).click();
    expect(dialog.open.mock.calls[0][1].data.message).toContain('Reject the suggestion for “Moby Dick”?');
    http.expectOne({ method: 'POST', url: `${QUEUE}/repo/rec-1/rejection` }).flush({ ...PENDING, status: 'REJECTED' });
    expect(snackBar.open.mock.calls[0][0]).toBe('Rejected the suggestion for “Moby Dick”');
    expectQueue().flush(page([FAILED]));
    const after = await settle();

    (card(after, 0).querySelector('.ui-review-retry') as HTMLButtonElement).click();
    http.expectOne({ method: 'POST', url: `${QUEUE}/other/rec-3/retry` }).flush({ ...FAILED, status: 'PENDING', failure: null });
    expectQueue().flush(page([]));
    await settle();
  });

  it('does nothing when a rejection is not confirmed', async () => {
    dialog.open.mockReturnValue({ afterClosed: () => of(false) });
    const element = await render(page([PENDING]));
    (card(element, 0).querySelector('.ui-review-reject') as HTMLButtonElement).click();
    http.expectNone(`${QUEUE}/repo/rec-1/rejection`);
    expect(snackBar.open).not.toHaveBeenCalled();
  });

  it('creates a proposed collection once confirmed, then places the record in it', async () => {
    const element = await render(page([PROPOSED]));
    (card(element, 0).querySelector('.ui-review-create') as HTMLButtonElement).click();
    expect(dialog.open.mock.calls[0][1].data.message).toContain('A new collection “Ship logs” in Library');

    // nothing exists at the proposed name yet
    http.expectOne(`${TEST_BASE}communities/lib/ship-logs`).flush('', { status: 404, statusText: 'Not Found' });
    const create = http.expectOne({ method: 'POST', url: `${TEST_BASE}communities/lib` });
    expect(create.request.headers.get('Slug')).toBe('ship-logs');
    expect(create.request.body).toContain('"Ship logs"');
    expect(create.request.body).toContain('"Logbooks of ships."');
    create.flush('', { status: 201, statusText: 'Created', headers: { Location: `${TEST_BASE}communities/lib/ship-logs` } });

    const accept = http.expectOne({ method: 'POST', url: `${QUEUE}/repo/rec-2/acceptance` });
    expect(accept.request.body).toEqual({ collection: 'communities/lib/ship-logs', topology: 'none' });
    accept.flush({ ...PROPOSED, status: 'ACCEPTED' });
    expectQueue().flush(page([]));
    await settle();
  });

  it('reports refused decisions and shows the queue again', async () => {
    const element = await render(page([PENDING]));
    (card(element, 0).querySelector('.ui-review-accept') as HTMLButtonElement).click();
    http
      .expectOne(`${QUEUE}/repo/rec-1/acceptance`)
      .flush({ detail: 'The collection is gone', reason: 'MISSING_COLLECTION' }, { status: 409, statusText: 'Conflict' });
    expect(snackBar.open).toHaveBeenCalled();
    expectQueue().flush(page([PENDING]));
    await settle();
  });

  it('shows the suggestions without decisions to readers', async () => {
    const element = await render(page([PENDING], false));
    expect(element.querySelector('.ui-review-read-only')?.textContent).toContain('may not decide');
    expect(element.querySelector('mat-card-actions')).toBeNull();
    expect(element.querySelector('.ui-review-alternatives')).toBeNull();
  });

  it('pages through the queue', async () => {
    const element = await render(page([PENDING], true, 30));
    expect(element.querySelector('.ui-review-pages')?.textContent).toContain('1–1 of 30');
    const next = [...element.querySelectorAll<HTMLButtonElement>('.ui-review-pages button')][1];
    next.click();
    fixture.detectChanges();
    TestBed.tick();
    expectQueue('status=open&offset=25&limit=25').flush({ ...page([FAILED], true, 30), offset: 25 });
    const after = await settle();
    expect(after.querySelector('.ui-review-pages')?.textContent).toContain('26–26 of 30');
  });
});
