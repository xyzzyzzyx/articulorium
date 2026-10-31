import { TestBed } from '@angular/core/testing';
import { provideRouter } from '@angular/router';

import { Term } from '../core/rdf/term';
import { TEST_BASE } from '../testing/test-config';
import { TermComponent } from './term.component';

describe('TermComponent', () => {
  const render = (term: Term): HTMLElement => {
    TestBed.configureTestingModule({ imports: [TermComponent], providers: [provideRouter([])] });
    const fixture = TestBed.createComponent(TermComponent);
    fixture.componentRef.setInput('term', term);
    fixture.componentRef.setInput('base', TEST_BASE);
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  };

  it('links repository IRIs to their browse view', () => {
    const link = render({ kind: 'iri', value: `${TEST_BASE}books/1` }).querySelector('a');

    expect(link?.getAttribute('href')).toBe('/browse/books/1');
    expect(link?.textContent).toBe('/books/1');
  });

  it('shows other IRIs by prefixed name', () => {
    const link = render({ kind: 'iri', value: 'http://purl.org/dc/terms/Text' }).querySelector('a');

    expect(link?.textContent).toBe('dcterms:Text');
    expect(link?.getAttribute('target')).toBe('_blank');
  });

  it('annotates literals', () => {
    const text = render({ kind: 'literal', value: '635', datatype: 'http://www.w3.org/2001/XMLSchema#integer' }).textContent;

    expect(text).toContain('635');
    expect(text).toContain('^^xsd:integer');
  });
});
