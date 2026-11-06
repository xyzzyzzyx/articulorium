import { PLATFORM, unique } from '../support/e2e';

/**
 * Harvesting into the platform: the harvest service runs the demo sources of the compose stack,
 * which harvest this repository itself, through its OAI-PMH data provider (self-oai) and through
 * its LDP API as a Fedora repository (self-ldp, below /harvest-source), and stores the copies
 * below /harvested/<source>/.
 *
 * Harvests are started with the actuator endpoint on the harvest service's management port, which
 * the gateway does not expose; cy.request runs in the Cypress process of the e2e container, which
 * resolves the compose service names.
 */

/** The harvest service's actuator endpoint. */
const HARVEST = 'http://harvest:8000/actuator/harvest';

/** The container of the harvested records (articulorium.harvest.container). */
const HARVESTED = `${PLATFORM}harvested`;

/** The root container the self-ldp source harvests below. */
const LDP_SOURCE = 'harvest-source';

const DCTERMS_TITLE = 'http://purl.org/dc/terms/title';
const LDP_CONTAINS = 'http://www.w3.org/ns/ldp#contains';
const ART = 'https://articulorium.org/ns/repository#';
const PROV_WAS_DERIVED_FROM = 'http://www.w3.org/ns/prov#wasDerivedFrom';

/** The part of a harvest report the tests read. */
interface Report {
  readonly outcome: string;
  readonly mode: string;
  readonly startedAt: string;
  readonly from: string | null;
  readonly checkpoint: string | null;
  readonly harvested: number;
  readonly failed: number;
  readonly failures: { identifier: string; reason: string }[];
}

/** The part of a source's state the tests read. */
interface Source {
  readonly id: string;
  readonly running: unknown;
  readonly reports: Report[];
}

/** Starts a harvest and yields when it was asked for. */
function startHarvest(source: string, mode: 'full' | 'incremental'): Cypress.Chainable<number> {
  const requested = Date.now();
  return cy
    .request({ method: 'POST', url: `${HARVEST}/${source}`, body: { mode } })
    .then((response) => {
      expect(response.status).to.eq(202);
      return requested;
    });
}

/**
 * Waits until a source has a report of a harvest started after an instant.
 *
 * @param source - the source identifier
 * @param after - epoch milliseconds the harvest was requested at
 * @param attempt - the current attempt
 * @returns the report
 */
function report(source: string, after: number, attempt = 1): Cypress.Chainable<Report> {
  return cy.request<Source>(`${HARVEST}/${source}`).then((response) => {
    const latest = response.body.reports[0];
    // a second of slack: the service's clock and this one may differ slightly
    if (!response.body.running && latest && Date.parse(latest.startedAt) >= after - 1000) {
      return cy.wrap(latest);
    }
    if (attempt >= 60) {
      throw new Error(`no report of a harvest of ${source} after ${new Date(after).toISOString()}`);
    }
    return cy.wait(1000).then(() => report(source, after, attempt + 1));
  });
}

/** Yields the N-Triples of a resource, through the gateway. */
function ntriples(path: string): Cypress.Chainable<string> {
  return cy
    .request({ url: path, headers: { Accept: 'application/n-triples' } })
    .then((response) => response.body as string);
}

/** Yields the IRIs a container contains. */
function contained(path: string): Cypress.Chainable<string[]> {
  return ntriples(path).then((body) =>
    body
      .split('\n')
      .filter((line) => line.includes(`<${LDP_CONTAINS}>`))
      .map((line) => /<[^>]+>\s+<[^>]+>\s+<([^>]+)>/.exec(line)?.[1] ?? '')
      .filter((iri) => iri !== ''),
  );
}

/**
 * Yields the IRI of the copy of a record: copies are named after the record's identifier (in
 * lower-case letters, digits and hyphens) followed by a hash.
 */
function copyOf(source: string, identifier: string): Cypress.Chainable<string> {
  const readable = identifier.toLowerCase().replace(/[^a-z0-9]+/g, '-').replace(/^-+|-+$/g, '');
  return contained(`${HARVESTED}/${source}`).then((iris) => {
    const matches = iris.filter((iri) => new RegExp(`/${readable}-[0-9a-f]{12}$`).test(iri));
    expect(matches, `the copy of ${identifier} in ${source}`).to.have.length(1);
    return matches[0];
  });
}

describe('Harvesting', () => {
  it('lists the configured sources', () => {
    cy.request<{ sources: Source[] }>(HARVEST).then((response) => {
      expect(response.body.sources.map((source) => source.id)).to.include.members(['self-oai', 'self-ldp']);
    });
  });

  it('harvests the repository through its own OAI-PMH data provider, then incrementally', () => {
    const path = unique('harvest-oai');
    const title = `Harvested through OAI-PMH ${path}`;
    // the data provider exposes pcdm:Object resources as records
    cy.request({
      method: 'PUT',
      url: `${PLATFORM}${path}`,
      headers: { 'Content-Type': 'text/turtle' },
      body: `<> a <http://pcdm.org/models#Object> ; <${DCTERMS_TITLE}> "${title}"@en .`,
    });

    startHarvest('self-oai', 'full')
      .then((requested) => report('self-oai', requested))
      .then((full) => {
        expect(full.outcome, JSON.stringify(full.failures)).to.eq('COMPLETED');
        expect(full.mode).to.eq('FULL');
        expect(full.from).to.eq(null);
        expect(full.checkpoint).to.be.a('string');
        expect(full.harvested).to.be.greaterThan(0);
      });

    copyOf('self-oai', `oai:articulorium.org:${path}`).then((iri) => {
      ntriples(iri.substring(iri.indexOf(PLATFORM))).then((body) => {
        expect(body).to.contain(`<${DCTERMS_TITLE}> "${title}"@en`);
        expect(body).to.contain(`<http://www.w3.org/2000/01/rdf-schema#label> "${title}"@en`);
        expect(body).to.contain(`<${ART}harvestIdentifier> "oai:articulorium.org:${path}"`);
        expect(body).to.contain(`<${ART}harvestSource> "self-oai"`);
        expect(body).to.contain(`<${ART}HarvestedRecord>`);
      });
    });

    // the next harvest starts from the checkpoint the first one saved
    startHarvest('self-oai', 'incremental')
      .then((requested) => report('self-oai', requested))
      .then((incremental) => {
        expect(incremental.outcome).to.eq('COMPLETED');
        expect(incremental.mode).to.eq('INCREMENTAL');
        expect(incremental.from).to.be.a('string');
      });
  });

  it('harvests the repository as a Fedora repository and refuses a second harvest of a running source', () => {
    const name = unique('fedora');
    const path = `${LDP_SOURCE}/${name}`;
    const title = `Harvested through the LDP API ${name}`;
    // the root the self-ldp source harvests below (it may exist from an earlier run)
    cy.request({
      method: 'PUT',
      url: `${PLATFORM}${LDP_SOURCE}`,
      headers: { 'Content-Type': 'text/turtle', 'If-None-Match': '*' },
      body: `<> <${DCTERMS_TITLE}> "Harvest source" .`,
      failOnStatusCode: false,
    }).then((response) => expect(response.status).to.be.oneOf([201, 412]));
    cy.request({
      method: 'PUT',
      url: `${PLATFORM}${path}`,
      headers: { 'Content-Type': 'text/turtle' },
      body: `<> <${DCTERMS_TITLE}> "${title}" ; <http://purl.org/dc/terms/subject> "harvesting" .`,
    });

    startHarvest('self-ldp', 'incremental').then((requested) => {
      // the source is busy until its harvest ends
      cy.request({ method: 'POST', url: `${HARVEST}/self-ldp`, body: {}, failOnStatusCode: false }).then(
        (second) => expect(second.status).to.be.oneOf([202, 409]),
      );
      report('self-ldp', requested).then((done) => {
        expect(done.outcome, JSON.stringify(done.failures)).to.eq('COMPLETED');
        expect(done.harvested).to.be.greaterThan(0);
      });
    });

    copyOf('self-ldp', path).then((iri) => {
      ntriples(iri.substring(iri.indexOf(PLATFORM))).then((body) => {
        expect(body).to.contain(`<${DCTERMS_TITLE}> "${title}"`);
        expect(body).to.contain(`<${ART}harvestIdentifier> "${path}"`);
        expect(body).to.contain(`<${PROV_WAS_DERIVED_FROM}> <http://platform:9000/${path}>`);
        expect(body).to.match(new RegExp(`<${ART}harvestDatestamp> "[^"]+"\\^\\^<http://www.w3.org/2001/XMLSchema#dateTime>`));
      });
    });
  });

  it('refuses harvests of unknown sources and unknown modes', () => {
    cy.request({ method: 'POST', url: `${HARVEST}/${unique('nope')}`, body: {}, failOnStatusCode: false })
      .its('status')
      .should('eq', 404);
    cy.request({ method: 'POST', url: `${HARVEST}/self-oai`, body: { mode: 'sideways' }, failOnStatusCode: false })
      .its('status')
      .should('eq', 400);
  });
});
