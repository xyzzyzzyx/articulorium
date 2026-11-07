import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { provideNoopAnimations } from '@angular/platform-browser/animations';
import { provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { HarvestReport, HarvestSource } from '../core/harvest/harvest';
import { TEST_HARVEST, provideTestConfig } from '../testing/test-config';
import { provideTestI18n } from '../testing/test-i18n';
import { HARVEST_POLL_INTERVAL, HarvestPageComponent } from './harvest-page.component';

const SOURCES = `${TEST_HARVEST}sources`;

const REPORT: HarvestReport = {
  sourceId: 'repo',
  type: 'oai-pmh',
  mode: 'INCREMENTAL',
  outcome: 'COMPLETED',
  startedAt: '2026-11-06T10:00:00Z',
  finishedAt: '2026-11-06T10:02:05Z',
  from: '2026-11-05T10:00:00Z',
  checkpoint: '2026-11-06T09:55:00Z',
  pages: 3,
  harvested: 120,
  created: 100,
  updated: 15,
  deleted: 5,
  skipped: 0,
  failed: 0,
  failures: [],
  message: null,
};

const REPO: HarvestSource = {
  id: 'repo',
  type: 'oai-pmh',
  url: 'https://repo.test/oai',
  enabled: true,
  schedule: '0 0 3 * * *',
  capabilities: { incremental: true, deletions: true },
  running: null,
  reports: [REPORT],
};

const PROFILES: HarvestSource = {
  id: 'profiles',
  type: 'vivo',
  url: 'https://vivo.test/vivo/',
  enabled: false,
  schedule: null,
  capabilities: { incremental: false, deletions: true },
  running: null,
  reports: [],
};

const RUNNING = { mode: 'FULL' as const, startedAt: '2026-11-06T11:00:00Z', harvested: 40, failed: 1, cancelled: false };

describe('HarvestPageComponent', () => {
  let http: HttpTestingController;
  const dialog = { open: vi.fn() };
  const snackBar = { open: vi.fn() };

  let pollInterval = 60_000;

  beforeEach(() => {
    pollInterval = 60_000;
    dialog.open.mockReset();
    snackBar.open.mockReset();
    TestBed.configureTestingModule({
      imports: [HarvestPageComponent],
      providers: [
        ...provideTestI18n(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideNoopAnimations(),
        provideRouter([]),
        provideTestConfig(),
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
        // tests that follow running harvests shorten it
        { provide: HARVEST_POLL_INTERVAL, useFactory: () => pollInterval },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render(sources: readonly HarvestSource[], operate = true) {
    const fixture = TestBed.createComponent(HarvestPageComponent);
    fixture.detectChanges();
    TestBed.tick();
    http.expectOne({ method: 'GET', url: SOURCES }).flush({ sources, operate });
    await settle(fixture);
    return { fixture, element: fixture.nativeElement as HTMLElement };
  }

  async function settle(fixture: { whenStable: () => Promise<unknown>; detectChanges: () => void }) {
    await fixture.whenStable();
    fixture.detectChanges();
    TestBed.tick();
    fixture.detectChanges();
  }

  /** Renders without waiting for stability, which never comes while the page follows a harvest. */
  async function flushView(fixture: { detectChanges: () => void }) {
    TestBed.tick();
    await Promise.resolve();
    fixture.detectChanges();
  }

  const sleep = (milliseconds: number) => new Promise((resolve) => setTimeout(resolve, milliseconds));

  const card = (element: HTMLElement, id: string) => element.querySelector(`.ui-harvest-source[data-source="${id}"]`) as HTMLElement;

  const button = (element: HTMLElement, id: string, kind: string) =>
    card(element, id).querySelector(`.ui-harvest-${kind}`) as HTMLButtonElement | null;

  it('shows the review queue of the AI agent only when the harvest service classifies records', async () => {
    const without = await render([REPO]);
    expect(without.element.querySelector('ui-review-queue')).toBeNull();
    without.fixture.destroy();

    const fixture = TestBed.createComponent(HarvestPageComponent);
    fixture.detectChanges();
    TestBed.tick();
    http.expectOne({ method: 'GET', url: SOURCES }).flush({ sources: [REPO, PROFILES], operate: true, classification: true });
    // the queue loads once rendered: the page is not stable before it is answered
    await flushView(fixture);
    await flushView(fixture);
    const queue = http.expectOne((request) => request.url === `${TEST_HARVEST}classifications`);
    expect(queue.request.params.get('status')).toBe('open');
    queue.flush({ classifications: [], total: 0, offset: 0, decide: true });
    await settle(fixture);
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('ui-review-queue h2')?.textContent).toContain('Review harvested records');
    expect(element.querySelector('.ui-review-empty')?.textContent).toContain('No records are waiting for review.');
  });

  it('lists the sources with what each supports and the last report', async () => {
    const { element } = await render([REPO, PROFILES]);

    const repo = card(element, 'repo');
    expect(repo.textContent).toContain('OAI-PMH');
    expect(repo.textContent).toContain('https://repo.test/oai');
    expect(repo.textContent).toContain('Incremental harvests');
    expect(repo.textContent).toContain('Reports deletions');
    expect(repo.textContent).toContain('Scheduled: 0 0 3 * * *');
    expect(repo.querySelector('.ui-outcome')?.textContent).toContain('Completed');
    expect(repo.querySelector('.ui-outcome-success')).not.toBeNull();
    expect(repo.querySelector('.ui-count-harvested')?.textContent).toBe('120');
    expect(repo.querySelector('.ui-count-created')?.textContent).toBe('100');
    expect(repo.querySelector('.ui-count-deleted')?.textContent).toBe('5');
    expect(repo.textContent).toContain('2 min 5 sec');
    expect(repo.querySelector('.ui-harvest-checkpoint')?.textContent).toContain('Checkpoint saved');
    expect(button(element, 'repo', 'incremental')?.disabled).toBe(false);
    expect(button(element, 'repo', 'full')?.disabled).toBe(false);
    expect(button(element, 'repo', 'cancel')).toBeNull();

    const profiles = card(element, 'profiles');
    expect(profiles.textContent).toContain('VIVO');
    expect(profiles.textContent).toContain('Disabled');
    expect(profiles.textContent).toContain('Full harvests only');
    expect(profiles.textContent).toContain('On demand only');
    expect(profiles.textContent).toContain('Not harvested since the harvest service started.');
    expect(button(element, 'profiles', 'incremental')?.disabled).toBe(true);
    expect(button(element, 'profiles', 'full')?.disabled).toBe(true);
    expect(profiles.querySelector('.ui-harvest-why')?.textContent).toContain('disabled');
    expect(element.querySelector('.ui-harvest-read-only')).toBeNull();
  });

  it('never shows an unsuccessful harvest as a success and lists its failures', async () => {
    const failed: HarvestReport = {
      ...REPORT,
      outcome: 'COMPLETED_WITH_ERRORS',
      checkpoint: null,
      failed: 3,
      failures: [
        { identifier: 'oai:repo:7', reason: 'Unmappable metadata' },
        { identifier: 'oai:repo:9', reason: 'The platform answered 500' },
      ],
    };
    const earlier: HarvestReport = { ...REPORT, outcome: 'FAILED', message: 'Source unavailable', startedAt: '2026-11-05T10:00:00Z' };
    const { element } = await render([{ ...REPO, reports: [failed, earlier] }]);
    const repo = card(element, 'repo');

    expect(repo.querySelector('.ui-harvest-report .ui-outcome')?.textContent).toContain('Completed with errors');
    expect(repo.querySelector('.ui-harvest-report .ui-outcome-warning')).not.toBeNull();
    expect(repo.querySelector('.ui-harvest-report .ui-outcome-success')).toBeNull();
    expect(repo.querySelector('.ui-harvest-checkpoint')?.textContent).toContain('Checkpoint not moved');
    expect(repo.querySelector('.ui-harvest-failures-panel')?.textContent).toContain('Failed records (3)');
    expect(repo.querySelector('.ui-harvest-failures-panel')?.textContent).toContain('Showing the first 2 of 3 failed records.');
    expect(repo.querySelector('.ui-harvest-failures')?.textContent).toContain('oai:repo:7: Unmappable metadata');
    expect(repo.querySelector('.ui-harvest-earlier')?.textContent).toContain('Earlier harvests (1)');
    expect(repo.querySelector('.ui-harvest-history .ui-outcome-error')?.textContent).toContain('Failed');
  });

  it('shows why a harvest failed', async () => {
    const failed: HarvestReport = { ...REPORT, outcome: 'FAILED', checkpoint: null, message: 'The platform answered 403 to store /harvested' };
    const { element } = await render([{ ...REPO, reports: [failed] }]);

    const message = card(element, 'repo').querySelector('.ui-harvest-message');
    expect(message?.textContent).toContain('The platform answered 403 to store /harvested');
    expect(message?.classList).toContain('ui-error-text');
    expect(card(element, 'repo').querySelector('.ui-outcome-error')).not.toBeNull();
  });

  it('starts incremental harvests and follows them until they end', async () => {
    pollInterval = 30;
    const { fixture, element } = await render([REPO]);

    button(element, 'repo', 'incremental')!.click();
    const start = http.expectOne({ method: 'POST', url: `${SOURCES}/repo/harvests` });
    expect(start.request.body).toEqual({ mode: 'incremental' });
    start.flush({ ...REPO, running: { ...RUNNING, mode: 'INCREMENTAL' } });
    await flushView(fixture);

    expect(dialog.open).not.toHaveBeenCalled();
    expect(snackBar.open.mock.calls[0][0]).toBe('Harvest of repo started');
    expect(card(element, 'repo').querySelector('.ui-harvest-running')?.textContent).toContain('Incremental harvest running');
    expect(button(element, 'repo', 'incremental')?.disabled).toBe(true);
    expect(button(element, 'repo', 'cancel')?.disabled).toBe(false);

    // still running: the page reads the sources again
    await sleep(60);
    http.expectOne({ method: 'GET', url: SOURCES }).flush({ sources: [{ ...REPO, running: { ...RUNNING, harvested: 80 } }], operate: true });
    await flushView(fixture);
    expect(card(element, 'repo').querySelector('.ui-harvest-running dd')?.textContent).toBe('80');

    // ended: no more requests
    await sleep(60);
    http.expectOne({ method: 'GET', url: SOURCES }).flush({ sources: [REPO], operate: true });
    await flushView(fixture);
    await sleep(60);
    http.expectNone(SOURCES);
    expect(card(element, 'repo').querySelector('.ui-harvest-running')).toBeNull();
  });

  it('starts full harvests only once confirmed', async () => {
    const { fixture, element } = await render([REPO]);

    dialog.open.mockReturnValue({ afterClosed: () => of(false) });
    button(element, 'repo', 'full')!.click();
    http.expectNone(`${SOURCES}/repo/harvests`);
    expect(dialog.open.mock.calls[0][1].data.message).toContain('reads every record of repo again');

    dialog.open.mockReturnValue({ afterClosed: () => of(true) });
    button(element, 'repo', 'full')!.click();
    const start = http.expectOne({ method: 'POST', url: `${SOURCES}/repo/harvests` });
    expect(start.request.body).toEqual({ mode: 'full' });
    start.flush({ ...REPO, running: RUNNING });
    await flushView(fixture);
    expect(card(element, 'repo').querySelector('.ui-harvest-running')?.textContent).toContain('Full harvest running');
  });

  it('cancels running harvests once confirmed', async () => {
    const { fixture, element } = await render([{ ...REPO, running: RUNNING }]);
    dialog.open.mockReturnValue({ afterClosed: () => of(true) });

    button(element, 'repo', 'cancel')!.click();
    http.expectOne({ method: 'DELETE', url: `${SOURCES}/repo/harvests/running` }).flush({
      ...REPO,
      running: { ...RUNNING, cancelled: true },
    });
    await flushView(fixture);

    expect(snackBar.open.mock.calls[0][0]).toBe('Harvest of repo is stopping');
    expect(card(element, 'repo').textContent).toContain('Stopping after the record in progress');
    expect(button(element, 'repo', 'cancel')?.disabled).toBe(true);
  });

  it('reports refused actions and shows the current state again', async () => {
    const { fixture, element } = await render([REPO]);

    button(element, 'repo', 'incremental')!.click();
    http
      .expectOne({ method: 'POST', url: `${SOURCES}/repo/harvests` })
      .flush(
        { title: 'Conflict', status: 409, detail: 'Harvest source repo is being harvested' },
        { status: 409, statusText: 'Conflict', headers: { 'Content-Type': 'application/problem+json', 'Content-Language': 'en' } },
      );
    await flushView(fixture);

    expect(snackBar.open.mock.calls[0][0]).toContain('Incremental harvest');
    expect(snackBar.open.mock.calls[0][0]).toContain('Harvest source repo is being harvested');
    http.expectOne({ method: 'GET', url: SOURCES }).flush({ sources: [{ ...REPO, running: RUNNING }], operate: true });
    await flushView(fixture);
    expect(button(element, 'repo', 'incremental')?.disabled).toBe(true);
  });

  it('lets readers follow harvests without offering actions', async () => {
    const { element } = await render([REPO], false);

    expect(element.querySelector('.ui-harvest-read-only')?.textContent).toContain('write access');
    expect(button(element, 'repo', 'incremental')?.disabled).toBe(true);
    expect(button(element, 'repo', 'full')?.disabled).toBe(true);
  });

  it('says when no source is configured', async () => {
    const { element } = await render([]);

    expect(element.querySelector('.ui-harvest-none')?.textContent).toContain('No harvest sources are configured');
  });

  it.each([
    [401, 'Sign in to see the harvests.'],
    [403, 'You cannot see the harvests'],
    [404, 'The harvest service cannot be reached'],
    [502, 'The harvest service cannot be reached'],
  ])('explains a %s answer', async (status, text) => {
    const fixture = TestBed.createComponent(HarvestPageComponent);
    fixture.detectChanges();
    TestBed.tick();
    http.expectOne(SOURCES).flush('', { status, statusText: 'Error' });
    await settle(fixture);

    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.ui-harvest-error')?.textContent).toContain(text);
    expect(element.querySelector('.ui-harvest-source')).toBeNull();
  });

  it('shows the harvest service problem when the platform cannot decide access, and retries', async () => {
    const fixture = TestBed.createComponent(HarvestPageComponent);
    fixture.detectChanges();
    TestBed.tick();
    http
      .expectOne(SOURCES)
      .flush(
        { title: 'Service Unavailable', status: 503, detail: 'Access to harvests cannot be checked: the platform is unavailable' },
        { status: 503, statusText: 'Service Unavailable', headers: { 'Content-Language': 'en' } },
      );
    await settle(fixture);
    const element = fixture.nativeElement as HTMLElement;
    expect(element.querySelector('.ui-harvest-error')?.textContent).toContain('the platform is unavailable');

    (element.querySelector('.ui-harvest-error button') as HTMLButtonElement).click();
    await flushView(fixture);
    http.expectOne(SOURCES).flush({ sources: [REPO], operate: true });
    await settle(fixture);
    expect(element.querySelector('.ui-harvest-error')).toBeNull();
    expect(card(element, 'repo')).not.toBeNull();
  });
});
