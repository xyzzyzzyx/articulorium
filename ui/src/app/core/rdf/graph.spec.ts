import { Graph, compact, lastSegment, prefixPreamble, subject } from './graph';
import { parseNTriples } from './ntriples';

const DOC = `
<http://h/p/books/1> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/ns/ldp#BasicContainer> .
<http://h/p/books/1> <http://purl.org/dc/terms/title> "Moby Dick" .
<http://h/p/books/1> <http://purl.org/dc/terms/subject> "whales" .
<http://h/p/books/1> <http://purl.org/dc/terms/subject> "sea" .
<http://h/p/books/1> <http://purl.org/dc/terms/creator> _:a .
_:a <http://xmlns.com/foaf/0.1/name> "Herman Melville" .
`;

describe('Graph', () => {
  const graph = new Graph(parseNTriples(DOC));
  const book = subject('http://h/p/books/1');

  it('groups properties by predicate in document order', () => {
    const groups = graph.properties(book);

    expect(groups.map((g) => g.predicate)).toEqual([
      'http://www.w3.org/1999/02/22-rdf-syntax-ns#type',
      'http://purl.org/dc/terms/title',
      'http://purl.org/dc/terms/subject',
      'http://purl.org/dc/terms/creator',
    ]);
    expect(groups[2].objects.map((o) => o.value)).toEqual(['whales', 'sea']);
  });

  it('answers type and value lookups', () => {
    expect(graph.hasType(book, 'http://www.w3.org/ns/ldp#BasicContainer')).toBe(true);
    expect(graph.value(book, 'http://purl.org/dc/terms/title')).toBe('Moby Dick');
    expect(graph.subjects().length).toBe(2);
  });

  it('chooses titles from title-like properties or the IRI', () => {
    expect(graph.title(book)).toBe('Moby Dick');
    expect(graph.title({ kind: 'bnode', value: 'a' })).toBe('Herman Melville');
    expect(graph.title(subject('http://h/p/books/a%20b'))).toBe('a b');
  });
});

describe('IRI helpers', () => {
  it('compacts IRIs with the longest namespace', () => {
    expect(compact('http://purl.org/dc/terms/title')).toBe('dcterms:title');
    expect(compact('http://www.w3.org/2001/XMLSchema#string')).toBe('xsd:string');
    expect(compact('http://unknown.org/x')).toBeUndefined();
    expect(compact('http://purl.org/dc/terms/has space')).toBeUndefined();
  });

  it('extracts last segments', () => {
    expect(lastSegment('http://h/p/books/')).toBe('books');
    expect(lastSegment('http://ex.org/ns#Thing')).toBe('Thing');
    expect(lastSegment('urn:x')).toBe('urn:x');
  });

  it('declares the prefixes a graph uses', () => {
    const preamble = prefixPreamble(new Graph(parseNTriples(DOC)));

    expect(preamble).toContain('PREFIX dcterms: <http://purl.org/dc/terms/>');
    expect(preamble).toContain('PREFIX ldp: <http://www.w3.org/ns/ldp#>');
    expect(preamble).toContain('PREFIX foaf: <http://xmlns.com/foaf/0.1/>');
    expect(preamble).not.toContain('pcdm');
  });
});
