import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { Topology, emptyNode } from '../core/topology/topology';
import { TEST_TOPOLOGY, provideTestConfig } from '../testing/test-config';
import { TopologyEditorPageComponent } from './topology-editor-page.component';
import { TopologyListPageComponent } from './topology-list-page.component';

const BOOK: Topology = { id: 'book', name: 'Book', builtIn: true, root: emptyNode('book', 'Book') };

describe('topology pages', () => {
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TopologyListPageComponent, TopologyEditorPageComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{ path: '**', children: [] }]), provideTestConfig()],
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
});
