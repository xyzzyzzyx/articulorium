import { DEFAULT_PREFIXES, RDF, TITLE_PROPERTIES } from './vocabulary';
import { IriTerm, SubjectTerm, Term, Triple, subjectKey } from './term';

/** The values of one predicate of a subject, for display. */
export interface PropertyGroup {
  readonly predicate: string;
  readonly objects: readonly Term[];
}

/**
 * An immutable set of triples with the lookups the resource views need.
 */
export class Graph {
  private readonly bySubject = new Map<string, Triple[]>();

  /**
   * @param triples - the triples of the graph
   */
  constructor(readonly triples: readonly Triple[]) {
    for (const triple of triples) {
      const key = subjectKey(triple.subject);
      const list = this.bySubject.get(key);
      if (list) {
        list.push(triple);
      } else {
        this.bySubject.set(key, [triple]);
      }
    }
  }

  /**
   * Returns the triples about a subject.
   *
   * @param subject - the subject
   * @returns its triples, in document order
   */
  about(subject: SubjectTerm): readonly Triple[] {
    return this.bySubject.get(subjectKey(subject)) ?? [];
  }

  /**
   * Returns the objects of a subject's predicate.
   *
   * @param subject - the subject
   * @param predicate - the predicate IRI
   * @returns the objects
   */
  objects(subject: SubjectTerm, predicate: string): Term[] {
    return this.about(subject)
      .filter((triple) => triple.predicate.value === predicate)
      .map((triple) => triple.object);
  }

  /**
   * Returns the lexical value of the first object of a subject's predicate.
   *
   * @param subject - the subject
   * @param predicate - the predicate IRI
   * @returns the value, or `undefined`
   */
  value(subject: SubjectTerm, predicate: string): string | undefined {
    return this.objects(subject, predicate)[0]?.value;
  }

  /**
   * Returns the `rdf:type`s of a subject.
   *
   * @param subject - the subject
   * @returns the type IRIs
   */
  types(subject: SubjectTerm): string[] {
    return this.objects(subject, RDF.type)
      .filter((term) => term.kind === 'iri')
      .map((term) => term.value);
  }

  /**
   * Tells whether a subject has a type.
   *
   * @param subject - the subject
   * @param type - the type IRI
   * @returns `true` when the subject is of that type
   */
  hasType(subject: SubjectTerm, type: string): boolean {
    return this.types(subject).includes(type);
  }

  /**
   * Returns the distinct subjects of the graph in document order.
   *
   * @returns the subjects
   */
  subjects(): SubjectTerm[] {
    return [...this.bySubject.values()].map((triples) => triples[0].subject);
  }

  /**
   * Groups a subject's triples by predicate, keeping document order.
   *
   * @param subject - the subject
   * @returns one group per predicate
   */
  properties(subject: SubjectTerm): PropertyGroup[] {
    const groups = new Map<string, Term[]>();
    for (const triple of this.about(subject)) {
      const objects = groups.get(triple.predicate.value);
      if (objects) {
        objects.push(triple.object);
      } else {
        groups.set(triple.predicate.value, [triple.object]);
      }
    }
    return [...groups.entries()].map(([predicate, objects]) => ({ predicate, objects }));
  }

  /**
   * Picks a human readable title for a subject: the first title-like property (Dublin Core title,
   * SKOS label, rdfs:label...) or, failing that, the last segment of its IRI.
   *
   * @param subject - the subject
   * @returns the title
   */
  title(subject: SubjectTerm): string {
    for (const property of TITLE_PROPERTIES) {
      const value = this.value(subject, property);
      if (value) {
        return value;
      }
    }
    return subject.kind === 'iri' ? lastSegment(subject.value) : `_:${subject.value}`;
  }
}

/**
 * Returns the last path segment (or fragment) of an IRI, decoded, for display.
 *
 * @param value - the IRI
 * @returns the segment, or the IRI itself when it has none
 */
export function lastSegment(value: string): string {
  const trimmed = value.replace(/\/+$/, '');
  const cut = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('#'));
  const segment = cut >= 0 ? trimmed.substring(cut + 1) : trimmed;
  if (!segment || /^[a-z][a-z0-9+.-]*:$/i.test(segment)) {
    return value;
  }
  try {
    return decodeURIComponent(segment);
  } catch {
    return segment;
  }
}

/**
 * Abbreviates an IRI with the longest matching namespace prefix (`dcterms:title`).
 *
 * @param value - the IRI
 * @param prefixes - prefix to namespace map
 * @returns the compact name, or `undefined` when no namespace matches
 */
export function compact(value: string, prefixes: Readonly<Record<string, string>> = DEFAULT_PREFIXES): string | undefined {
  let best: [string, string] | undefined;
  for (const [prefix, namespace] of Object.entries(prefixes)) {
    if (value.startsWith(namespace) && value.length > namespace.length && (!best || namespace.length > best[1].length)) {
      best = [prefix, namespace];
    }
  }
  if (!best) {
    return undefined;
  }
  const local = value.substring(best[1].length);
  return /^[A-Za-z_][\w.-]*$/.test(local) ? `${best[0]}:${local}` : undefined;
}

/**
 * Builds the `PREFIX` declarations of a SPARQL Update template from the namespaces a graph uses
 * (like Fedora's prefix preamble).
 *
 * @param graph - the graph
 * @param prefixes - prefix to namespace map
 * @returns one `PREFIX p: <ns>` line per used namespace, sorted by prefix
 */
export function prefixPreamble(graph: Graph, prefixes: Readonly<Record<string, string>> = DEFAULT_PREFIXES): string {
  const used = new Set<string>();
  const consider = (term: Term): void => {
    if (term.kind !== 'iri') {
      return;
    }
    const name = compact(term.value, prefixes);
    if (name) {
      used.add(name.substring(0, name.indexOf(':')));
    }
  };
  for (const triple of graph.triples) {
    consider(triple.predicate);
    consider(triple.object);
  }
  return [...used]
    .sort()
    .map((prefix) => `PREFIX ${prefix}: <${prefixes[prefix]}>`)
    .join('\n');
}

/**
 * Convenience: the IRI term of a string.
 *
 * @param value - the IRI
 * @returns the term
 */
export function subject(value: string): IriTerm {
  return { kind: 'iri', value };
}
