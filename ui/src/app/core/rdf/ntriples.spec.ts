import { NTriplesSyntaxError, parseNTriples } from './ntriples';

describe('parseNTriples', () => {
  it('parses IRIs, blank nodes and literals', () => {
    const triples = parseNTriples(
      [
        '# comment',
        '<http://ex.org/a> <http://ex.org/p> <http://ex.org/b> .',
        '_:b0 <http://ex.org/p> "plain" .',
        '<http://ex.org/a> <http://ex.org/q> "Moby Dick"@en-GB .',
        '<http://ex.org/a> <http://ex.org/r> "635"^^<http://www.w3.org/2001/XMLSchema#integer> .',
        '<http://ex.org/a> <http://ex.org/s> _:b.1 .',
        '',
      ].join('\n'),
    );

    expect(triples.length).toBe(5);
    expect(triples[0].object).toEqual({ kind: 'iri', value: 'http://ex.org/b' });
    expect(triples[1].subject).toEqual({ kind: 'bnode', value: 'b0' });
    expect(triples[2].object).toEqual({ kind: 'literal', value: 'Moby Dick', language: 'en-GB' });
    expect(triples[3].object).toEqual({
      kind: 'literal',
      value: '635',
      datatype: 'http://www.w3.org/2001/XMLSchema#integer',
    });
    expect(triples[4].object).toEqual({ kind: 'bnode', value: 'b.1' });
  });

  it('decodes escape sequences', () => {
    const [triple] = parseNTriples(
      '<http://ex.org/\\u00E9> <http://ex.org/p> "line\\nbreak \\"quoted\\" \\u00e9\\U0001F600" .',
    );

    expect(triple.subject.value).toBe('http://ex.org/é');
    expect(triple.object.value).toBe('line\nbreak "quoted" é😀');
  });

  it('accepts CRLF line endings and trailing comments', () => {
    const triples = parseNTriples('<a:x> <a:p> "v" . # note\r\n<a:x> <a:p> "w" .\r\n');

    expect(triples.map((t) => t.object.value)).toEqual(['v', 'w']);
  });

  it('reports the line of syntax errors', () => {
    expect(() => parseNTriples('<a:x> <a:p> "v" .\n<a:x> <a:p> "unterminated .')).toThrowError(NTriplesSyntaxError);
    try {
      parseNTriples('<a:x> <a:p> "v" .\n<a:x> "p" "v" .');
    } catch (e) {
      expect((e as NTriplesSyntaxError).line).toBe(2);
    }
    expect(() => parseNTriples('<a:x> <a:p> "v"')).toThrowError(/expected "\."/);
    expect(() => parseNTriples('<a:x> <a:p> "\\q" .')).toThrowError(/escape/);
  });
});
