/**
 * Small helpers building SPARQL Update requests from user input.
 */

/**
 * Writes an IRI for SPARQL.
 *
 * @param iri - the IRI
 * @returns `<iri>` with characters not allowed in IRIs escaped
 */
export function sparqlIri(iri: string): string {
  return `<${iri.replace(/[<>"{}|^`\\\s]/g, (c) => `%${c.charCodeAt(0).toString(16).toUpperCase().padStart(2, '0')}`)}>`;
}

/**
 * Writes a literal for SPARQL.
 *
 * @param value - the lexical form
 * @param datatype - the datatype IRI, if any
 * @returns the quoted, escaped literal
 */
export function sparqlLiteral(value: string, datatype?: string | null): string {
  const escaped = value
    .replace(/\\/g, '\\\\')
    .replace(/"/g, '\\"')
    .replace(/\n/g, '\\n')
    .replace(/\r/g, '\\r')
    .replace(/\t/g, '\\t');
  return datatype ? `"${escaped}"^^${sparqlIri(datatype)}` : `"${escaped}"`;
}

/**
 * Builds an update replacing every value of some predicates of a subject.
 *
 * @param subject - the subject IRI
 * @param values - new literal values by predicate (an empty list removes the predicate)
 * @param datatypes - optional datatype by predicate
 * @returns the SPARQL Update, or an empty string when nothing changes
 */
export function replaceValuesUpdate(
  subject: string,
  values: Readonly<Record<string, readonly string[]>>,
  datatypes: Readonly<Record<string, string | null | undefined>> = {},
): string {
  const predicates = Object.keys(values);
  if (predicates.length === 0) {
    return '';
  }
  const s = sparqlIri(subject);
  const parts = predicates.map((predicate) => `DELETE WHERE { ${s} ${sparqlIri(predicate)} ?o }`);
  const inserts = predicates.flatMap((predicate) =>
    values[predicate]
      .map((value) => value.trim())
      .filter(Boolean)
      .map((value) => `${s} ${sparqlIri(predicate)} ${sparqlLiteral(value, datatypes[predicate])} .`),
  );
  if (inserts.length > 0) {
    parts.push(`INSERT DATA { ${inserts.join(' ')} }`);
  }
  return parts.join(' ;\n');
}

/**
 * Builds an update adding types to a resource.
 *
 * @param subject - the subject IRI
 * @param types - the type IRIs
 * @returns the SPARQL Update
 */
export function addTypesUpdate(subject: string, types: readonly string[]): string {
  const s = sparqlIri(subject);
  return `INSERT DATA { ${types.map((type) => `${s} a ${sparqlIri(type)} .`).join(' ')} }`;
}
