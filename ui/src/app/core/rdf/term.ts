/**
 * RDF terms and triples as parsed from N-Triples.
 */

/** An IRI. */
export interface IriTerm {
  readonly kind: 'iri';
  readonly value: string;
}

/** A blank node; `value` is its label without the `_:` prefix. */
export interface BlankNodeTerm {
  readonly kind: 'bnode';
  readonly value: string;
}

/** A literal with an optional datatype IRI or language tag. */
export interface LiteralTerm {
  readonly kind: 'literal';
  readonly value: string;
  readonly datatype?: string;
  readonly language?: string;
}

/** Any RDF term. */
export type Term = IriTerm | BlankNodeTerm | LiteralTerm;

/** A subject: an IRI or a blank node. */
export type SubjectTerm = IriTerm | BlankNodeTerm;

/** An RDF triple. */
export interface Triple {
  readonly subject: SubjectTerm;
  readonly predicate: IriTerm;
  readonly object: Term;
}

/**
 * Creates an IRI term.
 *
 * @param value - the IRI
 * @returns the term
 */
export function iri(value: string): IriTerm {
  return { kind: 'iri', value };
}

/**
 * Creates a literal term.
 *
 * @param value - the lexical form
 * @param datatype - the datatype IRI, if any
 * @param language - the language tag, if any
 * @returns the term
 */
export function literal(value: string, datatype?: string, language?: string): LiteralTerm {
  return { kind: 'literal', value, datatype, language };
}

/**
 * Tells whether two terms are equal.
 *
 * @param a - a term
 * @param b - another term
 * @returns `true` when kind, value, datatype and language match
 */
export function sameTerm(a: Term, b: Term): boolean {
  if (a.kind !== b.kind || a.value !== b.value) {
    return false;
  }
  if (a.kind === 'literal' && b.kind === 'literal') {
    return a.datatype === b.datatype && a.language === b.language;
  }
  return true;
}

/**
 * Returns a stable key identifying a subject (used to group triples).
 *
 * @param term - the subject
 * @returns `<iri>` or `_:label`
 */
export function subjectKey(term: SubjectTerm): string {
  return term.kind === 'iri' ? `<${term.value}>` : `_:${term.value}`;
}
