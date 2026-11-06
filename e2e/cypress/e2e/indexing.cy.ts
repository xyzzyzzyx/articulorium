import { PLATFORM, unique } from '../support/e2e';

/**
 * The event pipeline behind search and SPARQL: every LDP change is published on the resource
 * bus, dispatched through the Spring Integration event flows of the transform and triplestore
 * services, turned into a search document on the document bus and applied by the index service.
 * These tests change resources through the LDP API and watch the stores the pipeline maintains.
 *
 * The stores are queried directly on the compose network (cy.request runs in the Cypress
 * process of the e2e container, which resolves the compose service names).
 */

/** Solr collection the index service writes (articulorium.solr.*). */
const SOLR = 'http://solr:8983/solr/articulorium';

/** QLever SPARQL endpoint the triplestore service writes (articulorium.triplestore.*). */
const QLEVER = 'http://qlever:7001/';

/** How long the pipeline may take to apply a change. */
const ATTEMPTS = 30;

/** The IRI the platform mints for a path below the root, through the gateway. */
function iriOf(path: string): string {
  return `${Cypress.config('baseUrl')}${PLATFORM}${path}`;
}

/** A Solr document: field names to values. */
type SolrDocument = Record<string, unknown>;

/** The part of a Solr select response the tests read. */
interface SolrSelect {
  readonly response: { readonly docs: SolrDocument[] };
}

/** The part of SPARQL 1.1 JSON query results the tests read. */
interface SparqlResults {
  readonly results: { readonly bindings: Record<string, { readonly value: string } | undefined>[] };
}

/** Parses a JSON response body, whatever content type it was served with. */
function json<T>(body: unknown): T {
  return (typeof body === 'string' ? JSON.parse(body) : body) as T;
}

/** The values of a field of the first of some Solr documents (single or multi-valued). */
function values(documents: SolrDocument[], field: string): unknown[] {
  return ([] as unknown[]).concat(documents[0]?.[field] ?? []);
}

/** Yields the Solr documents of a resource: one when it is indexed, none otherwise. */
function solrDocuments(iri: string): Cypress.Chainable<SolrDocument[]> {
  return cy
    .request({ url: `${SOLR}/select`, qs: { q: `id:"${iri}"`, wt: 'json' } })
    .then((response) => json<SolrSelect>(response.body).response.docs);
}

/** Yields the number of triples in the named graph of a resource. */
function graphSize(iri: string): Cypress.Chainable<number> {
  return cy
    .request({
      url: QLEVER,
      qs: { query: `SELECT (COUNT(*) AS ?n) WHERE { GRAPH <${iri}> { ?s ?p ?o } }` },
      headers: { Accept: 'application/sparql-results+json' },
    })
    .then((response) => Number(json<SparqlResults>(response.body).results.bindings[0]?.['n']?.value ?? 0));
}

/**
 * Re-evaluates a query until its value satisfies a condition: the pipeline is asynchronous.
 *
 * @param description - what is awaited, for the failure message
 * @param query - yields the current value
 * @param done - whether the value is the expected one
 * @param attempt - the current attempt
 */
function eventually<T>(
  description: string,
  query: () => Cypress.Chainable<T>,
  done: (value: T) => boolean,
  attempt = 1,
): Cypress.Chainable<T> {
  return query().then((value) => {
    if (done(value)) {
      return cy.wrap(value, { log: false });
    }
    if (attempt >= ATTEMPTS) {
      throw new Error(`Timed out waiting for ${description} (last value: ${JSON.stringify(value)})`);
    }
    return cy.wait(1000, { log: false }).then(() => eventually(description, query, done, attempt + 1));
  });
}

describe('Indexing pipeline', () => {
  it('indexes, updates and removes the search document of a resource', () => {
    const name = unique('indexed');
    const iri = iriOf(name);
    const title = unique('Moby Dick');

    cy.createContainer(title, name);
    eventually(`the Solr document of ${iri}`, () => solrDocuments(iri), (docs) => docs.length === 1).then((docs) => {
      expect(values(docs, 'title')).to.deep.eq([title]);
    });

    cy.request({
      method: 'PATCH',
      url: `${PLATFORM}${name}`,
      headers: { 'Content-Type': 'application/sparql-update' },
      body: `INSERT DATA { <> <http://purl.org/dc/terms/title> "Ishmael" }`,
    });
    eventually(
      `the updated Solr document of ${iri}`,
      () => solrDocuments(iri),
      (docs) => values(docs, 'title').includes('Ishmael'),
    );

    cy.request({ method: 'DELETE', url: `${PLATFORM}${name}` });
    eventually(`the removal of the Solr document of ${iri}`, () => solrDocuments(iri), (docs) => docs.length === 0);
  });

  it('mirrors a resource into its named graph and drops it on delete', () => {
    const name = unique('mirrored');
    const iri = iriOf(name);

    cy.createContainer('Mirrored', name);
    eventually(`the named graph of ${iri}`, () => graphSize(iri), (size) => size > 0);

    cy.request({ method: 'DELETE', url: `${PLATFORM}${name}` });
    eventually(`the removal of the named graph of ${iri}`, () => graphSize(iri), (size) => size === 0);
  });
});
