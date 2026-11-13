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

/**
 * The search index the index service writes (articulorium.index.backend): Solr, or Elasticsearch
 * when the stack runs with docker-compose.elasticsearch.yml, which sets E2E_INDEX_BACKEND
 * (docker/run.sh passes it on: cypress run --expose indexBackend=elasticsearch,...).
 */
const BACKEND = String(Cypress.expose('indexBackend') ?? 'solr').toLowerCase();

/** Solr collection the index service writes (articulorium.solr.*). */
const SOLR = 'http://solr:8983/solr/articulorium';

/** Elasticsearch index the index service writes (articulorium.elasticsearch.*). */
const ELASTICSEARCH = 'http://elasticsearch:9200/articulorium';

/**
 * The SPARQL store the triplestore service writes (articulorium.triplestore.backend): QLever, or
 * Blazegraph or Apache Jena Fuseki when the stack runs with docker-compose.blazegraph.yml or
 * docker-compose.jena.yml, which set E2E_TRIPLESTORE_BACKEND (docker/run.sh passes it on:
 * cypress run --expose indexBackend=solr,triplestoreBackend=jena).
 */
const TRIPLESTORE = String(Cypress.expose('triplestoreBackend') ?? 'qlever').toLowerCase();

/** SPARQL query endpoints of the stores (articulorium.triplestore.*), by backend. */
const SPARQL_ENDPOINTS: Record<string, string> = {
  qlever: 'http://qlever:7001/',
  blazegraph: 'http://blazegraph:8080/bigdata/namespace/articulorium/sparql',
  jena: 'http://fuseki:3030/articulorium/sparql',
};

/** SPARQL query endpoint of the store the stack runs with. */
function sparqlEndpoint(): string {
  const endpoint = SPARQL_ENDPOINTS[TRIPLESTORE];
  if (!endpoint) {
    throw new Error(`Unknown triplestore backend ${TRIPLESTORE}`);
  }
  return endpoint;
}

/** How long the pipeline may take to apply a change. */
const ATTEMPTS = 30;

/** The IRI the platform mints for a path below the root, through the gateway. */
function iriOf(path: string): string {
  return `${Cypress.config('baseUrl')}${PLATFORM}${path}`;
}

/** A search document: field names to values, the same in Solr and Elasticsearch. */
type IndexDocument = Record<string, unknown>;

/** The part of a Solr select response the tests read. */
interface SolrSelect {
  readonly response: { readonly docs: IndexDocument[] };
}

/** The part of an Elasticsearch search response the tests read. */
interface ElasticsearchSearch {
  readonly hits: { readonly hits: { readonly _source: IndexDocument }[] };
}

/** The part of SPARQL 1.1 JSON query results the tests read. */
interface SparqlResults {
  readonly results: { readonly bindings: Record<string, { readonly value: string } | undefined>[] };
}

/** Parses a JSON response body, whatever content type it was served with. */
function json<T>(body: unknown): T {
  return (typeof body === 'string' ? JSON.parse(body) : body) as T;
}

/** The values of a field of the first of some search documents (single or multi-valued). */
function values(documents: IndexDocument[], field: string): unknown[] {
  return ([] as unknown[]).concat(documents[0]?.[field] ?? []);
}

/** Yields the Elasticsearch documents with an exact value in a field. */
function elasticsearchDocuments(field: string, value: string): Cypress.Chainable<IndexDocument[]> {
  return cy
    .request({ method: 'POST', url: `${ELASTICSEARCH}/_search`, body: { query: { term: { [field]: value } } } })
    .then((response) => json<ElasticsearchSearch>(response.body).hits.hits.map((hit) => hit._source));
}

/** Yields the search documents of a resource: one when it is indexed, none otherwise. */
function indexDocuments(iri: string): Cypress.Chainable<IndexDocument[]> {
  if (BACKEND === 'elasticsearch') {
    return elasticsearchDocuments('id', iri);
  }
  return cy
    .request({ url: `${SOLR}/select`, qs: { q: `id:"${iri}"`, wt: 'json' } })
    .then((response) => json<SolrSelect>(response.body).response.docs);
}

/** Yields the number of triples in the named graph of a resource. */
function graphSize(iri: string): Cypress.Chainable<number> {
  return cy
    .request({
      url: sparqlEndpoint(),
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
    eventually(`the search document of ${iri}`, () => indexDocuments(iri), (docs) => docs.length === 1).then((docs) => {
      expect(values(docs, 'title')).to.deep.eq([title]);
    });
    if (BACKEND === 'elasticsearch') {
      // the index mappings keep an exact-value copy of text fields, as Solr's _default config set
      // does: the discovery layer's facets, filters and sort orders rely on <field>_str
      eventually(
        `the exact-value copy of the title of ${iri}`,
        () => elasticsearchDocuments('title_str', title),
        (docs) => docs.length === 1,
      );
    }

    cy.request({
      method: 'PATCH',
      url: `${PLATFORM}${name}`,
      headers: { 'Content-Type': 'application/sparql-update' },
      body: `INSERT DATA { <> <http://purl.org/dc/terms/title> "Ishmael" }`,
    });
    eventually(
      `the updated search document of ${iri}`,
      () => indexDocuments(iri),
      (docs) => values(docs, 'title').includes('Ishmael'),
    );

    cy.request({ method: 'DELETE', url: `${PLATFORM}${name}` });
    eventually(`the removal of the search document of ${iri}`, () => indexDocuments(iri), (docs) => docs.length === 0);
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
