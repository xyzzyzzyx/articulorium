import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatSnackBar } from '@angular/material/snack-bar';

import { ProfileSuggestion } from '../core/ai/ai';
import { ResourceView } from '../core/ldp/models';
import { Profile, emptyProfile, profileTurtle, readProfile } from '../core/profile/profile';
import { Graph, subject } from '../core/rdf/graph';
import { parseNTriples } from '../core/rdf/ntriples';
import { parseWacAllow } from '../core/webac/webac';
import { aiCapabilities } from '../testing/test-ai';
import { TEST_AI, TEST_BASE, provideTestConfig } from '../testing/test-config';
import { provideTestI18n } from '../testing/test-i18n';
import { ProfileSuggestionComponent } from './profile-suggestion.component';

const CURIE = `${TEST_BASE}profiles/marie-curie`;

/** A statement the editor does not model: kept as it is when the profile is saved. */
const OTHER = `<${CURIE}> <http://example.org/ns#favouriteElement> "polonium" .\n`;

function curie(overview = 'Physicist.'): Profile {
  return {
    ...emptyProfile(),
    givenName: 'Marie',
    familyName: 'Curie',
    overview,
    keywords: ['radium'],
    researchAreas: ['Radioactivity'],
    publications: [{ title: 'Radioactive substances', type: 'Book', venue: '', year: '1904', doi: '' }],
  };
}

/** The profile's resource as the platform answers it, read like the profile page does. */
function view(profile: Profile, wac = 'user="read write"'): ResourceView {
  const nt = profileTurtle(profile).replace(/<>/g, `<${CURIE}>`).replace(/<#/g, `<${CURIE}#`) + OTHER;
  return {
    path: 'profiles/marie-curie',
    subject: subject(CURIE),
    url: CURIE,
    graph: new Graph(parseNTriples(nt)),
    allow: new Set(['GET', 'PUT']),
    linkTypes: [],
    etag: 'W/"v1"',
    wacAllow: parseWacAllow(wac),
  };
}

const SUGGESTION: ProfileSuggestion = {
  path: 'profiles/marie-curie',
  overview: 'Marie Curie studies radioactivity and isolated polonium and radium.',
  hasOverview: true,
  researchAreas: ['Radiochemistry', 'Nuclear physics'],
  keywords: ['isolation of radium', 'pitchblende'],
  basedOn: { positions: 0, education: 0, publications: 1 },
  language: 'en',
};

describe('ProfileSuggestionComponent', () => {
  let fixture: ComponentFixture<ProfileSuggestionComponent>;
  let http: HttpTestingController;
  const snackBar = { open: vi.fn() };

  const render = (v: ResourceView): HTMLElement => {
    fixture = TestBed.createComponent(ProfileSuggestionComponent);
    fixture.componentRef.setInput('view', v);
    fixture.componentRef.setInput('document', readProfile(v.graph, v.subject));
    fixture.detectChanges();
    http.expectOne(`${TEST_AI}capabilities`).flush(aiCapabilities(['profiles']));
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  const click = (element: HTMLElement, selector: string): void => {
    (element.querySelector(selector) as HTMLElement).click();
    fixture.detectChanges();
  };

  const ask = (element: HTMLElement, answer: ProfileSuggestion): void => {
    click(element, '.ui-ai-suggest');
    const request = http.expectOne({ method: 'POST', url: `${TEST_AI}profile-suggestions` });
    expect(request.request.body).toEqual({ path: 'profiles/marie-curie' });
    request.flush(answer);
    fixture.detectChanges();
  };

  beforeEach(() => {
    snackBar.open.mockReset();
    TestBed.configureTestingModule({
      imports: [ProfileSuggestionComponent],
      providers: [...provideTestI18n(), provideHttpClient(), provideHttpClientTesting(), provideTestConfig(), { provide: MatSnackBar, useValue: snackBar }],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('is offered only to users who may change the profile', () => {
    expect(render(view(curie(), 'user="read"')).querySelector('.ui-profile-suggestion')).toBeNull();
  });

  it('merges what the user keeps into the profile and saves it as the editor does', () => {
    const element = render(view(curie()));
    ask(element, SUGGESTION);

    // replacing an overview is the user's choice: offered unkept, with a warning
    const overview = element.querySelector('.ui-ai-overview input[type="checkbox"]') as HTMLInputElement;
    expect(overview.checked).toBe(false);
    expect(element.querySelector('.ui-ai-replaces')?.textContent).toContain('replaces the current overview');
    expect([...element.querySelectorAll('.ui-ai-areas li')].map((li) => li.textContent?.trim())).toEqual(['Radiochemistry', 'Nuclear physics']);

    click(element, '.ui-ai-overview input[type="checkbox"]');
    click(element, '.ui-ai-keywords li:nth-child(2) input[type="checkbox"]');
    click(element, '.ui-ai-apply');

    const put = http.expectOne({ method: 'PUT', url: CURIE });
    expect(put.request.headers.get('If-Match')).toBe('W/"v1"');
    const body = put.request.body as string;
    expect(body).toContain('"Marie Curie studies radioactivity and isolated polonium and radium."');
    expect(body).not.toContain('"Physicist."');
    expect(body).toContain('"Radiochemistry"');
    expect(body).toContain('"Nuclear physics"');
    expect(body).toContain('"Radioactivity"');
    expect(body).toContain('"isolation of radium"');
    expect(body).toContain('"radium"');
    expect(body).not.toContain('pitchblende');
    // statements the editor does not model are kept
    expect(body).toContain('favouriteElement');
    expect(body).toContain('"Radioactive substances"');

    let applied = false;
    fixture.componentInstance.applied.subscribe(() => (applied = true));
    put.flush('');
    fixture.detectChanges();
    expect(applied).toBe(true);
    expect(snackBar.open.mock.calls[0][0]).toBe('Profile updated');
    expect(element.querySelector('.ui-ai-apply')).toBeNull();
  });

  it('offers a missing overview kept, and keeps the current one when it is dropped', () => {
    const element = render(view(curie('')));
    ask(element, { ...SUGGESTION, hasOverview: false, researchAreas: [], keywords: [] });
    expect((element.querySelector('.ui-ai-overview input[type="checkbox"]') as HTMLInputElement).checked).toBe(true);
    expect(element.querySelector('.ui-ai-replaces')).toBeNull();

    click(element, '.ui-ai-overview input[type="checkbox"]');
    // nothing kept: nothing to save
    expect((element.querySelector('.ui-ai-apply') as HTMLButtonElement).disabled).toBe(true);
  });

  it('asks for positions, degrees or publications when the profile has none', () => {
    const element = render(view(curie()));
    ask(element, { ...SUGGESTION, overview: '', researchAreas: [], keywords: [], basedOn: { positions: 0, education: 0, publications: 0 } });
    expect(element.querySelector('.ui-ai-nothing')?.textContent).toContain('Add positions, degrees or publications');
    expect(element.querySelector('.ui-ai-apply')).toBeNull();
  });

  it('says when there is nothing to add, and explains failures', () => {
    const element = render(view(curie()));
    ask(element, { ...SUGGESTION, overview: '', researchAreas: [], keywords: [] });
    expect(element.querySelector('.ui-ai-nothing')?.textContent).toContain('found nothing to add');

    click(element, '.ui-ai-discard');
    click(element, '.ui-ai-suggest');
    http
      .expectOne(`${TEST_AI}profile-suggestions`)
      .flush({ reason: 'BUSY', detail: 'busy' }, { status: 429, statusText: 'Too Many Requests' });
    fixture.detectChanges();
    expect(element.querySelector('[role="alert"]')?.textContent).toContain('The AI agent is busy.');
  });

  it('reports a profile changed meanwhile instead of overwriting it', () => {
    const element = render(view(curie()));
    ask(element, SUGGESTION);
    click(element, '.ui-ai-apply');
    http
      .expectOne({ method: 'PUT', url: CURIE })
      .flush({ detail: 'The resource was changed' }, { status: 412, statusText: 'Precondition Failed' });
    fixture.detectChanges();
    expect(snackBar.open).toHaveBeenCalled();
    // the suggestion stays for another try
    expect(element.querySelector('.ui-ai-apply')).not.toBeNull();
  });
});
