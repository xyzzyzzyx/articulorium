import { addTypesUpdate, replaceValuesUpdate, sparqlIri, sparqlLiteral } from './sparql';

describe('sparql', () => {
  it('escapes IRIs and literals', () => {
    expect(sparqlIri('http://x/a b')).toBe('<http://x/a%20b>');
    expect(sparqlLiteral('say "hi"\n')).toBe('"say \\"hi\\"\\n"');
    expect(sparqlLiteral('2026', 'http://www.w3.org/2001/XMLSchema#gYear')).toBe(
      '"2026"^^<http://www.w3.org/2001/XMLSchema#gYear>',
    );
  });

  it('replaces values of predicates', () => {
    const update = replaceValuesUpdate('http://x/s', { 'http://p/a': ['one', ' ', 'two'], 'http://p/b': [] });
    expect(update).toBe(
      'DELETE WHERE { <http://x/s> <http://p/a> ?o } ;\n' +
        'DELETE WHERE { <http://x/s> <http://p/b> ?o } ;\n' +
        'INSERT DATA { <http://x/s> <http://p/a> "one" . <http://x/s> <http://p/a> "two" . }',
    );
    expect(replaceValuesUpdate('http://x/s', {})).toBe('');
  });

  it('adds types', () => {
    expect(addTypesUpdate('http://x/f', ['http://t/A', 'http://t/B'])).toBe(
      'INSERT DATA { <http://x/f> a <http://t/A> . <http://x/f> a <http://t/B> . }',
    );
  });
});
