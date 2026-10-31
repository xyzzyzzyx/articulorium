import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { ResourceView } from '../core/ldp/models';
import { Graph } from '../core/rdf/graph';
import { parseNTriples } from '../core/rdf/ntriples';
import { ART, LDP, PCDM, RDF } from '../core/rdf/vocabulary';
import { Topology, emptyNode } from '../core/topology/topology';
import { TEST_BASE, TEST_TOPOLOGY, provideTestConfig } from '../testing/test-config';
import { TopologyPanelComponent } from './topology-panel.component';

const BOOK: Topology = {
  id: 'book',
  name: 'Book',
  builtIn: true,
  root: { ...emptyNode('book', 'Book'), children: [{ min: 1, max: -1, initial: 2, slugPattern: 'page-{n}', node: emptyNode('page', 'Page') }] },
};

function view(nt: string, path: string): ResourceView {
  const graph = new Graph(parseNTriples(nt));
  return {
    path,
    subject: { kind: 'iri', value: `${TEST_BASE}${path}` },
    url: `${TEST_BASE}${path}`,
    graph,
    allow: new Set(['GET', 'POST', 'PATCH']),
    linkTypes: [LDP.basicContainer, LDP.container],
  };
}

describe('TopologyPanelComponent', () => {
  let fixture: ComponentFixture<TopologyPanelComponent>;
  let http: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      imports: [TopologyPanelComponent],
      providers: [provideHttpClient(), provideHttpClientTesting(), provideRouter([{ path: '**', children: [] }]), provideTestConfig()],
    });
    http = TestBed.inject(HttpTestingController);
    fixture = TestBed.createComponent(TopologyPanelComponent);
  });

  afterEach(() => http.verify());

  it('shows the members of a built resource and adds children', async () => {
    const s = `<${TEST_BASE}c/book>`;
    fixture.componentRef.setInput(
      'view',
      view(
        `${s} <${ART.topology}> "book" .\n${s} <${ART.topologyNode}> "book" .\n` +
          `${s} <${PCDM.hasMember}> <${TEST_BASE}c/book/members/page-10> .\n` +
          `${s} <${PCDM.hasMember}> <${TEST_BASE}c/book/members/page-2> .\n`,
        'c/book',
      ),
    );
    fixture.detectChanges();
    TestBed.tick();
    http.expectOne(`${TEST_TOPOLOGY}topologies/book`).flush(BOOK);
    await fixture.whenStable();
    fixture.detectChanges();
    const element = fixture.nativeElement as HTMLElement;

    expect([...element.querySelectorAll('.ui-topology-members a')].map((a) => a.textContent)).toEqual(['page-2', 'page-10']);
    const add = [...element.querySelectorAll('button')].find((button) => button.textContent?.includes('Add Page'));
    expect(add).toBeDefined();
    add?.click();
    const request = http.expectOne({ method: 'POST', url: `${TEST_TOPOLOGY}topologies/book/members` });
    expect(request.request.body).toEqual({ parent: 'c/book', nodeKey: 'page', title: undefined });
    request.flush({ path: 'c/book/members/page-3', topology: 'book', nodeKey: 'page', resources: [] });
  });

  it('builds a topology in a plain container', async () => {
    fixture.componentRef.setInput('view', view(`<${TEST_BASE}c> <${RDF.type}> <${LDP.basicContainer}> .\n`, 'c'));
    fixture.componentRef.setInput('expanded', true);
    fixture.detectChanges();
    TestBed.tick();
    http.expectOne(`${TEST_TOPOLOGY}topologies`).flush([{ id: 'book', name: 'Book', builtIn: true, depth: 2 }]);
    await fixture.whenStable();

    const component = fixture.componentInstance as unknown as {
      selectedId: { set(id: string): void };
      title: string;
      counts: Record<string, number>;
      build(): void;
    };
    component.selectedId.set('book');
    fixture.detectChanges();
    TestBed.tick();
    http.expectOne(`${TEST_TOPOLOGY}topologies/book`).flush(BOOK);
    await fixture.whenStable();
    expect(component.counts).toEqual({ page: 2 });
    component.title = 'Moby Dick';
    component.counts = { page: 5 };
    component.build();
    const request = http.expectOne({ method: 'POST', url: `${TEST_TOPOLOGY}topologies/book/instances` });
    expect(request.request.body).toEqual({ parent: 'c', title: 'Moby Dick', counts: { page: 5 } });
    request.flush({ path: 'c/moby', topology: 'book', nodeKey: 'book', resources: ['c/moby'] });
  });
});
