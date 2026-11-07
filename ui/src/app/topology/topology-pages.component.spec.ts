import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { I18nService } from '../core/i18n/i18n.service';
import { Topology, emptyNode } from '../core/topology/topology';
import { TEST_TOPOLOGY, provideTestConfig } from '../testing/test-config';
import { TopologyEditorPageComponent } from './topology-editor-page.component';
import { TopologyListPageComponent } from './topology-list-page.component';
import { provideTestI18n } from '../testing/test-i18n';
import { offerAi, provideTestAi } from '../testing/test-ai';
import { AiService, TopologyDraftStore } from '../core/ai/ai.service';
import { MatDialog } from '@angular/material/dialog';
import { TopologySuggestionDialogComponent } from '../ai/topology-suggestion-dialog.component';

const BOOK: Topology = { id: 'book', name: 'Book', builtIn: true, root: emptyNode('book', 'Book') };

describe('topology pages', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TopologyListPageComponent, TopologyEditorPageComponent],
      providers: [...provideTestI18n(), provideHttpClient(), provideHttpClientTesting(), provideRouter([{ path: '**', children: [] }]), provideTestConfig(), provideTestAi()],
    });
    http = TestBed.inject(HttpTestingController);
  });

  afterEach(() => http.verify());

  it('lists built-in and custom topologies', async () => {
    const fixture = TestBed.createComponent(TopologyListPageComponent);
    fixture.detectChanges();
    TestBed.tick();
    http.expectOne(`${TEST_TOPOLOGY}topologies`).flush([
      { id: 'book', name: 'Book', builtIn: true, depth: 2, description: 'Pages' },
      { id: 'album', name: 'Album', builtIn: false, depth: 2 },
    ]);
    await fixture.whenStable();
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect([...element.querySelectorAll('.ui-topology-summary mat-card-title a')].map((a) => a.textContent)).toEqual([
      'Book',
      'Album',
    ]);
    expect(element.textContent).toContain('Built-in');
    expect(element.textContent).toContain('Custom');
  });

  it('loads the list again in the language the user switches to', async () => {
    const script = document.createElement('script');
    script.id = 'ui-i18n';
    script.type = 'application/json';
    const locales = [
      { tag: 'en', name: 'English', direction: 'ltr' },
      { tag: 'fr', name: 'Français', direction: 'ltr' },
    ];
    script.textContent = JSON.stringify({ locale: 'en', direction: 'ltr', defaultLocale: 'en', locales, messages: {} });
    document.head.appendChild(script);
    try {
      const i18n = TestBed.inject(I18nService);
      await i18n.init();
      const fixture = TestBed.createComponent(TopologyListPageComponent);
      fixture.detectChanges();
      TestBed.tick();
      http.expectOne(`${TEST_TOPOLOGY}topologies`).flush([{ id: 'book', name: 'Book', builtIn: true, depth: 2 }]);
      await fixture.whenStable();

      const switching = i18n.use('fr');
      http
        .expectOne('api/i18n/fr')
        .flush({ locale: 'fr', direction: 'ltr', defaultLocale: 'en', locales, messages: {} });
      await switching;
      fixture.detectChanges();
      TestBed.tick();
      http.expectOne(`${TEST_TOPOLOGY}topologies`).flush([{ id: 'book', name: 'Livre', builtIn: true, depth: 2 }]);
      await fixture.whenStable();
      fixture.detectChanges();

      expect((fixture.nativeElement as HTMLElement).querySelector('.ui-topology-summary mat-card-title a')?.textContent).toBe(
        'Livre',
      );
    } finally {
      script.remove();
      document.documentElement.lang = 'en';
      document.cookie = 'articulorium-locale=; path=/; max-age=0';
    }
  });

  it('shows built-in topologies read-only and saves copies as new topologies', async () => {
    const fixture = TestBed.createComponent(TopologyEditorPageComponent);
    fixture.componentRef.setInput('id', 'new');
    fixture.componentRef.setInput('from', 'book');
    fixture.detectChanges();
    TestBed.tick();
    http.expectOne(`${TEST_TOPOLOGY}topologies/book`).flush(BOOK);
    await fixture.whenStable();
    fixture.detectChanges();

    const component = fixture.componentInstance as unknown as {
      draft(): { id: string; name: string; builtIn: boolean };
      save(topology: unknown): void;
    };
    expect(component.draft()).toEqual(expect.objectContaining({ id: 'book-copy', name: 'Book (copy)', builtIn: false }));
    component.save(component.draft());
    const request = http.expectOne({ method: 'POST', url: `${TEST_TOPOLOGY}topologies` });
    expect((request.request.body as Topology).root.key).toBe('book');
    request.flush({ ...BOOK, id: 'book-copy', builtIn: false });
  });

  it('reports invalid topologies without sending them', async () => {
    const fixture = TestBed.createComponent(TopologyEditorPageComponent);
    fixture.componentRef.setInput('id', 'new');
    fixture.detectChanges();
    TestBed.tick();
    await fixture.whenStable();
    fixture.detectChanges();

    const component = fixture.componentInstance as unknown as { draft(): unknown; save(topology: unknown): void };
    component.save(component.draft());
    fixture.detectChanges();
    expect((fixture.nativeElement as HTMLElement).querySelector('.ui-topology-errors')?.textContent).toContain('identifier');
  });

  describe('with the AI agent', () => {
    it('offers topology suggestions only when the agent does', async () => {
      const without = TestBed.createComponent(TopologyListPageComponent);
      without.detectChanges();
      TestBed.tick();
      http.expectOne(`${TEST_TOPOLOGY}topologies`).flush([]);
      await without.whenStable();
      without.detectChanges();
      expect((without.nativeElement as HTMLElement).querySelector('.ui-ai-topology')).toBeNull();
    });

    it('opens the suggestion dialog from the list', async () => {
      offerAi(TestBed.inject(AiService), ['topology']);
      const dialog = TestBed.inject(MatDialog);
      const open = vi.spyOn(dialog, 'open').mockReturnValue(undefined as never);
      const fixture = TestBed.createComponent(TopologyListPageComponent);
      fixture.detectChanges();
      TestBed.tick();
      http.expectOne(`${TEST_TOPOLOGY}topologies`).flush([]);
      await fixture.whenStable();
      fixture.detectChanges();

      const button = (fixture.nativeElement as HTMLElement).querySelector('.ui-ai-topology') as HTMLButtonElement;
      expect(button.textContent).toContain('Suggest a topology');
      button.click();
      expect(open).toHaveBeenCalledWith(TopologySuggestionDialogComponent, expect.anything());
    });

    it('starts a new topology from the draft of the agent, once', async () => {
      TestBed.inject(TopologyDraftStore).offer({ id: 'letters', name: 'Letters', builtIn: false, root: emptyNode('letter', 'Letter') });
      const fixture = TestBed.createComponent(TopologyEditorPageComponent);
      fixture.componentRef.setInput('id', 'new');
      fixture.componentRef.setInput('origin', 'ai');
      fixture.detectChanges();
      TestBed.tick();
      await fixture.whenStable();
      fixture.detectChanges();

      const component = fixture.componentInstance as unknown as { draft(): Topology };
      expect(component.draft()).toEqual(expect.objectContaining({ id: 'letters', name: 'Letters', builtIn: false }));
      expect(TestBed.inject(TopologyDraftStore).take()).toBeUndefined();
    });
  });
});
