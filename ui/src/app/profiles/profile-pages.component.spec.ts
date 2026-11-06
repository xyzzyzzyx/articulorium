import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { MatDialog } from '@angular/material/dialog';
import { MatSnackBar } from '@angular/material/snack-bar';
import { Router, provideRouter } from '@angular/router';
import { of } from 'rxjs';

import { Profile, emptyProfile, profileTurtle } from '../core/profile/profile';
import { ART, FOAF, LDP, RDF, RDFS_LABEL } from '../core/rdf/vocabulary';
import { TEST_BASE, provideTestConfig } from '../testing/test-config';
import { provideTestI18n } from '../testing/test-i18n';
import { ProfileEditorPageComponent } from './profile-editor-page.component';
import { ProfileListPageComponent } from './profile-list-page.component';
import { ProfilePageComponent } from './profile-page.component';

const CURIE = `${TEST_BASE}profiles/marie-curie`;

/** The N-Triples the platform answers for a profile written by the editor. */
function stored(profile: Profile, subject = CURIE, extra = ''): string {
  const turtle = profileTurtle(profile).replace(/<>/g, `<${subject}>`).replace(/<#/g, `<${subject}#`);
  return `${turtle}<${subject}> <${RDF.type}> <${LDP.basicContainer}> .\n${extra}`;
}

function curie(): Profile {
  return {
    ...emptyProfile(),
    givenName: 'Marie',
    familyName: 'Curie',
    preferredTitle: 'Professor of Physics',
    email: 'marie@example.org',
    orcid: '0000-0002-1825-0097',
    overview: 'Pioneer of radioactivity.',
    keywords: ['radium'],
    researchAreas: ['Radioactivity'],
    positions: [{ title: 'Professor', type: 'FacultyPosition', organization: 'University of Paris', start: '1906', end: '' }],
    education: [{ degree: 'Doctor of Science', field: 'Physics', organization: 'University of Paris', end: '1903' }],
    publications: [{ title: 'Radioactive substances', type: 'Book', venue: '', year: '1904', doi: '10.1000/182' }],
  };
}

describe('profile pages', () => {
  let http: HttpTestingController;
  const dialog = { open: vi.fn() };
  const snackBar = { open: vi.fn() };

  beforeEach(() => {
    dialog.open.mockReset();
    snackBar.open.mockReset();
    TestBed.configureTestingModule({
      providers: [
        ...provideTestI18n(),
        provideHttpClient(),
        provideHttpClientTesting(),
        provideRouter([]),
        provideTestConfig(),
        { provide: MatDialog, useValue: dialog },
        { provide: MatSnackBar, useValue: snackBar },
      ],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  async function render<T>(fixture: ComponentFixture<T>): Promise<HTMLElement> {
    await fixture.whenStable();
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  describe('ProfileListPageComponent', () => {
    it('lists the profiles by family name, filters them and offers new ones', async () => {
      const fixture = TestBed.createComponent(ProfileListPageComponent);
      fixture.detectChanges();
      TestBed.tick();
      http.expectOne(`${TEST_BASE}profiles`).flush(
        `<${TEST_BASE}profiles> <${RDF.type}> <${ART.profileRoot}> .\n` +
          `<${TEST_BASE}profiles> <${LDP.contains}> <${CURIE}> .\n` +
          `<${TEST_BASE}profiles> <${LDP.contains}> <${TEST_BASE}profiles/ada> .\n` +
          `<${TEST_BASE}profiles> <${LDP.contains}> <${TEST_BASE}profiles/notes> .\n`,
        { headers: { 'WAC-Allow': 'user="read write append"' } },
      );
      TestBed.tick();
      http.expectOne(CURIE).flush(stored(curie()));
      http
        .expectOne(`${TEST_BASE}profiles/ada`)
        .flush(stored({ ...emptyProfile(), personType: 'Person', givenName: 'Ada', familyName: 'Lovelace' }, `${TEST_BASE}profiles/ada`));
      http.expectOne(`${TEST_BASE}profiles/notes`).flush(`<${TEST_BASE}profiles/notes> <${RDFS_LABEL}> "Notes" .\n`);
      const element = await render(fixture);

      const cards = [...element.querySelectorAll('.ui-profile-card')];
      expect(cards.map((card) => card.querySelector('a')?.textContent)).toEqual(['Marie Curie', 'Ada Lovelace']);
      expect(cards[0].querySelector('a')?.getAttribute('href')).toBe('/profiles/marie-curie');
      expect(cards[0].textContent).toContain('Professor of Physics');
      expect(cards[1].textContent).toContain('Person');
      expect(element.querySelector('.ui-new-profile')?.getAttribute('href')).toBe('/profiles/new');
      expect(element.querySelector('.ui-profile-filter .mdc-floating-label')?.classList).toContain(
        'mdc-floating-label--float-above',
      );

      const input = element.querySelector('.ui-profile-filter input') as HTMLInputElement;
      input.value = 'ada';
      input.dispatchEvent(new Event('input'));
      fixture.detectChanges();
      expect([...element.querySelectorAll('.ui-profile-card a')].map((a) => a.textContent)).toEqual(['Ada Lovelace']);
    });

    it('explains an empty container and hides creation without append access', async () => {
      const fixture = TestBed.createComponent(ProfileListPageComponent);
      fixture.detectChanges();
      TestBed.tick();
      http.expectOne(`${TEST_BASE}profiles`).flush(`<${TEST_BASE}profiles> <${RDF.type}> <${ART.profileRoot}> .\n`, {
        headers: { 'WAC-Allow': 'user="read"' },
      });
      const element = await render(fixture);
      expect(element.textContent).toContain('No profiles yet.');
      expect(element.querySelector('.ui-new-profile')).toBeNull();
    });
  });

  describe('ProfilePageComponent', () => {
    async function open(nt: string, wac = 'user="read write"'): Promise<{ element: HTMLElement; fixture: ComponentFixture<ProfilePageComponent> }> {
      const fixture = TestBed.createComponent(ProfilePageComponent);
      fixture.componentRef.setInput('id', 'marie-curie');
      fixture.detectChanges();
      TestBed.tick();
      http.expectOne(CURIE).flush(nt, { headers: { 'WAC-Allow': wac } });
      return { element: await render(fixture), fixture };
    }

    it('shows every part of a profile', async () => {
      const { element } = await open(stored(curie()));
      expect(element.querySelector('h1')?.textContent).toBe('Marie Curie');
      expect(element.querySelector('.ui-profile-title')?.textContent).toBe('Professor of Physics');
      expect(element.querySelector('.ui-profile-type')?.textContent).toBe('Faculty member');
      expect(element.querySelector('.ui-profile-orcid')?.getAttribute('href')).toBe('https://orcid.org/0000-0002-1825-0097');
      expect(element.querySelector('.ui-profile-contact')?.textContent).toContain('marie@example.org');
      expect(element.querySelector('.ui-profile-areas')?.textContent).toContain('Radioactivity');
      expect(element.querySelector('.ui-profile-keywords')?.textContent).toContain('radium');
      expect(element.querySelector('.ui-profile-positions')?.textContent).toContain('University of Paris');
      expect(element.querySelector('.ui-profile-positions')?.textContent).toContain('1906 – present');
      expect(element.querySelector('.ui-profile-education')?.textContent).toContain('Doctor of Science (Physics), University of Paris');
      expect(element.querySelector('.ui-profile-publications a')?.getAttribute('href')).toBe('https://doi.org/10.1000/182');
      expect(element.querySelector('.ui-edit-profile')?.getAttribute('href')).toBe('/profiles/marie-curie/edit');
    });

    it('offers no editing without write access', async () => {
      const { element } = await open(stored(curie()), 'user="read"');
      expect(element.querySelector('.ui-edit-profile')).toBeNull();
      expect(element.querySelector('.ui-delete-profile')).toBeNull();
    });

    it('says when a resource is not a profile', async () => {
      const { element } = await open(`<${CURIE}> <${RDFS_LABEL}> "Notes" .\n`);
      expect(element.textContent).toContain('This resource is not an academic profile.');
      expect(element.querySelector('.ui-edit-profile')).toBeNull();
    });

    it('deletes after confirmation and returns to the list', async () => {
      const { element } = await open(stored(curie()));
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      dialog.open.mockReturnValue({ afterClosed: () => of(true) });
      (element.querySelector('.ui-delete-profile') as HTMLButtonElement).click();
      expect(dialog.open.mock.calls[0][1].data.message).toBe('Delete the profile of Marie Curie? This cannot be undone.');
      http.expectOne({ method: 'DELETE', url: CURIE }).flush('', { status: 204, statusText: 'No Content' });
      expect(navigate).toHaveBeenCalledWith(['/profiles']);
    });
  });

  describe('ProfileEditorPageComponent', () => {
    type Editor = { draft: () => Profile | undefined };

    function submit(element: HTMLElement): void {
      (element.querySelector('form.ui-profile-editor') as HTMLFormElement).dispatchEvent(new Event('submit'));
    }

    it('explains what is missing instead of saving an incomplete profile', async () => {
      const fixture = TestBed.createComponent(ProfileEditorPageComponent);
      fixture.detectChanges();
      let element = await render(fixture);
      expect(element.querySelector('h1')?.textContent).toBe('New profile');
      expect(element.querySelector('mat-error')).toBeNull();

      const draft = (fixture.componentInstance as unknown as Editor).draft() as Profile;
      draft.email = 'marie-at-example';
      submit(element);
      element = await render(fixture);

      const summary = element.querySelector('.ui-profile-issues')?.textContent ?? '';
      expect(summary).toContain('Given name: This field is required');
      expect(summary).toContain('Family name: This field is required');
      expect(summary).toContain('Email: Enter an email address such as name@example.org');
      expect([...element.querySelectorAll('mat-error')].map((error) => error.textContent?.trim())).toEqual([
        'This field is required',
        'This field is required',
        'Enter an email address such as name@example.org',
      ]);
      expect(element.querySelector('input[name=familyName]')?.closest('mat-form-field')?.classList).toContain('mat-form-field-invalid');
      expect(element.querySelector('input[name=email]')?.getAttribute('aria-invalid')).toBe('true');
      expect(element.querySelector('input[name=givenName]')).toBe(document.activeElement);
    });

    it('keeps the labels of the narrow year fields above their inputs', async () => {
      const fixture = TestBed.createComponent(ProfileEditorPageComponent);
      fixture.detectChanges();
      let element = await render(fixture);
      (element.querySelector('.ui-add-position') as HTMLButtonElement).click();
      element = await render(fixture);
      const labels = [...element.querySelectorAll('.ui-profile-position .ui-profile-year .mdc-floating-label')];
      expect(labels).toHaveLength(2);
      expect(labels.every((label) => label.classList.contains('mdc-floating-label--float-above'))).toBe(true);
    });

    it('names invalid rows in the summary', async () => {
      const fixture = TestBed.createComponent(ProfileEditorPageComponent);
      fixture.detectChanges();
      let element = await render(fixture);
      const draft = (fixture.componentInstance as unknown as Editor).draft() as Profile;
      Object.assign(draft, { givenName: 'Marie', familyName: 'Curie' });
      draft.positions.push({ title: 'Professor', type: 'FacultyPosition', organization: 'Sorbonne', start: '1934', end: '1906' });
      submit(element);
      element = await render(fixture);
      expect(element.querySelector('.ui-profile-issues')?.textContent).toContain(
        'Position 1 · End year: The end year is before the start year',
      );
    });

    it('creates a profile in the profile container and shows it', async () => {
      const fixture = TestBed.createComponent(ProfileEditorPageComponent);
      fixture.detectChanges();
      const element = await render(fixture);
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);
      Object.assign((fixture.componentInstance as unknown as Editor).draft() as Profile, curie());
      submit(element);

      const request = http.expectOne({ method: 'POST', url: `${TEST_BASE}profiles` });
      expect(request.request.headers.get('Slug')).toBe('marie-curie');
      expect(request.request.headers.get('Link')).toBe(`<${LDP.basicContainer}>; rel="type"`);
      expect(request.request.body).toContain(`<> <${RDF.type}> <${FOAF.Person}> .`);
      request.flush('', { status: 201, statusText: 'Created', headers: { Location: CURIE } });
      expect(navigate).toHaveBeenCalledWith(['/profiles', 'marie-curie']);
      expect(snackBar.open.mock.calls[0][0]).toBe('Profile created');
    });

    it('replaces an edited profile with the entity tag it was read with, keeping other statements', async () => {
      const fixture = TestBed.createComponent(ProfileEditorPageComponent);
      fixture.componentRef.setInput('id', 'marie-curie');
      fixture.detectChanges();
      TestBed.tick();
      http
        .expectOne(CURIE)
        .flush(stored(curie(), CURIE, `<${CURIE}> <http://purl.org/dc/terms/subject> "physics" .\n`), { headers: { ETag: '"3"' } });
      let element = await render(fixture);
      expect(element.querySelector('h1')?.textContent).toBe('Marie Curie');
      expect((element.querySelector('input[name=familyName]') as HTMLInputElement).value).toBe('Curie');
      const navigate = vi.spyOn(TestBed.inject(Router), 'navigate').mockResolvedValue(true);

      const draft = (fixture.componentInstance as unknown as Editor).draft() as Profile;
      draft.preferredTitle = 'Director of the Radium Institute';
      draft.positions = [];
      submit(element);
      const request = http.expectOne({ method: 'PUT', url: CURIE });
      expect(request.request.headers.get('If-Match')).toBe('"3"');
      expect(request.request.body).toContain('"Director of the Radium Institute"');
      expect(request.request.body).toContain('<> <http://purl.org/dc/terms/subject> "physics" .');
      expect(request.request.body).not.toContain('#position-1');
      expect(request.request.body).not.toContain(LDP.basicContainer);
      request.flush('', { status: 204, statusText: 'No Content' });
      expect(navigate).toHaveBeenCalledWith(['/profiles', 'marie-curie']);

      // a concurrent change is reported, not overwritten
      submit(element);
      http.expectOne({ method: 'PUT', url: CURIE }).flush('', { status: 412, statusText: 'Precondition Failed' });
      element = await render(fixture);
      expect(snackBar.open.mock.calls.at(-1)?.[0]).toContain('Someone changed this profile after you opened it.');
    });
  });
});
