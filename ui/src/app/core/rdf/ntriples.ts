import { BlankNodeTerm, IriTerm, LiteralTerm, SubjectTerm, Term, Triple } from './term';

/**
 * Error raised for malformed N-Triples input.
 */
export class NTriplesSyntaxError extends Error {
  /**
   * @param message - what is wrong
   * @param line - the 1-based line number
   */
  constructor(
    message: string,
    readonly line: number,
  ) {
    super(`N-Triples line ${line}: ${message}`);
    this.name = 'NTriplesSyntaxError';
  }
}

/**
 * Parses an N-Triples document (W3C RDF 1.1 N-Triples), the representation the user interface
 * requests from the platform because it needs no prefix or context processing.
 *
 * @param text - the document
 * @returns the triples, in document order
 * @throws NTriplesSyntaxError when a line is malformed
 */
export function parseNTriples(text: string): Triple[] {
  const triples: Triple[] = [];
  const lines = text.split(/\r?\n/);
  lines.forEach((raw, index) => {
    const line = raw.trim();
    if (line === '' || line.startsWith('#')) {
      return;
    }
    triples.push(new LineParser(line, index + 1).triple());
  });
  return triples;
}

/** Recursive-descent parser for one N-Triples statement. */
class LineParser {
  private position = 0;

  constructor(
    private readonly text: string,
    private readonly line: number,
  ) {}

  triple(): Triple {
    const subject = this.subject();
    this.whitespace();
    const predicate = this.iri();
    this.whitespace();
    const object = this.object();
    this.whitespace();
    this.expect('.');
    this.whitespace();
    if (this.position < this.text.length && this.text[this.position] !== '#') {
      this.fail('unexpected content after "."');
    }
    return { subject, predicate, object };
  }

  private subject(): SubjectTerm {
    return this.peek() === '_' ? this.blankNode() : this.iri();
  }

  private object(): Term {
    switch (this.peek()) {
      case '<':
        return this.iri();
      case '_':
        return this.blankNode();
      case '"':
        return this.literal();
      default:
        return this.fail('expected an IRI, blank node or literal');
    }
  }

  private iri(): IriTerm {
    this.expect('<');
    let value = '';
    while (this.position < this.text.length && this.text[this.position] !== '>') {
      const ch = this.text[this.position];
      if (ch === '\\') {
        value += this.unicodeEscape();
      } else {
        value += ch;
        this.position++;
      }
    }
    this.expect('>');
    return { kind: 'iri', value };
  }

  private blankNode(): BlankNodeTerm {
    this.expect('_');
    this.expect(':');
    const start = this.position;
    while (this.position < this.text.length && !/\s/.test(this.text[this.position])) {
      this.position++;
    }
    // labels may contain "." but not end with one: a trailing "." terminates the statement
    while (this.position > start && this.text[this.position - 1] === '.') {
      this.position--;
    }
    if (this.position === start) {
      this.fail('empty blank node label');
    }
    return { kind: 'bnode', value: this.text.substring(start, this.position) };
  }

  private literal(): LiteralTerm {
    this.expect('"');
    let value = '';
    for (;;) {
      if (this.position >= this.text.length) {
        this.fail('unterminated literal');
      }
      const ch = this.text[this.position];
      if (ch === '"') {
        this.position++;
        break;
      }
      if (ch === '\\') {
        value += this.escape();
      } else {
        value += ch;
        this.position++;
      }
    }
    if (this.peek() === '@') {
      this.position++;
      const start = this.position;
      while (this.position < this.text.length && /[A-Za-z0-9-]/.test(this.text[this.position])) {
        this.position++;
      }
      return { kind: 'literal', value, language: this.text.substring(start, this.position) };
    }
    if (this.peek() === '^') {
      this.expect('^');
      this.expect('^');
      return { kind: 'literal', value, datatype: this.iri().value };
    }
    return { kind: 'literal', value };
  }

  private escape(): string {
    const next = this.text[this.position + 1];
    const simple: Record<string, string> = { t: '\t', b: '\b', n: '\n', r: '\r', f: '\f', '"': '"', "'": "'", '\\': '\\' };
    if (next !== undefined && next in simple) {
      this.position += 2;
      return simple[next];
    }
    return this.unicodeEscape();
  }

  private unicodeEscape(): string {
    const marker = this.text[this.position + 1];
    const length = marker === 'u' ? 4 : marker === 'U' ? 8 : 0;
    const hex = this.text.substring(this.position + 2, this.position + 2 + length);
    if (length === 0 || !/^[0-9A-Fa-f]+$/.test(hex) || hex.length !== length) {
      this.fail('invalid escape sequence');
    }
    this.position += 2 + length;
    return String.fromCodePoint(parseInt(hex, 16));
  }

  private whitespace(): void {
    while (this.position < this.text.length && /[ \t]/.test(this.text[this.position])) {
      this.position++;
    }
  }

  private peek(): string | undefined {
    return this.text[this.position];
  }

  private expect(ch: string): void {
    if (this.text[this.position] !== ch) {
      this.fail(`expected "${ch}"`);
    }
    this.position++;
  }

  private fail(message: string): never {
    throw new NTriplesSyntaxError(`${message} at column ${this.position + 1}`, this.line);
  }
}
