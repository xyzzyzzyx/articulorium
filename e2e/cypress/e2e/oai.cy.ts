import { PLATFORM, unique } from '../support/e2e';

/** The OAI-PMH base URL through the gateway. */
const OAI = '/oai';

/** Namespaces of OAI-PMH responses. */
const NS = {
  oai: 'http://www.openarchives.org/OAI/2.0/',
  dc: 'http://purl.org/dc/elements/1.1/',
};

/** Prefix of the repository's item identifiers (articulorium.oai.repository-identifier). */
const ID = 'oai:articulorium.org:';

/**
 * Sends an OAI-PMH request and parses the response, which must be OAI-PMH XML.
 *
 * @param query - the request arguments
 * @returns the parsed document
 */
function oai(query: Record<string, string>): Cypress.Chainable<Document> {
  return cy.request({ url: OAI, qs: query }).then((response) => {
    expect(response.status).to.eq(200);
    expect(response.headers['content-type']).to.match(/^text\/xml/);
    const document = new DOMParser().parseFromString(response.body as string, 'text/xml');
    expect(document.getElementsByTagName('parsererror')).to.have.length(0);
    expect(document.documentElement.localName).to.eq('OAI-PMH');
    return document;
  });
}

/** Text of every element with a name in a namespace. */
function texts(document: Document, namespace: string, name: string): string[] {
  return Array.from(document.getElementsByTagNameNS(namespace, name)).map((element) => element.textContent ?? '');
}

/** The OAI-PMH error code of a response, if any. */
function errorCode(document: Document): string | null {
  return document.getElementsByTagNameNS(NS.oai, 'error')[0]?.getAttribute('code') ?? null;
}

/**
 * Follows resumption tokens until a list holds a value or ends.
 *
 * @returns every value of the element across the parts read
 */
function harvest(query: Record<string, string>, name: string, wanted: string, found: string[] = []): Cypress.Chainable<string[]> {
  return oai(query).then((document) => {
    found.push(...texts(document, NS.oai, name));
    const token = texts(document, NS.oai, 'resumptionToken')[0];
    if (found.includes(wanted) || !token) {
      return cy.wrap(found);
    }
    return harvest({ verb: query['verb'], resumptionToken: token }, name, wanted, found);
  });
}

/** Creates a container with types and a title through the LDP API; yields its path. */
function createTyped(path: string, title: string, types: string[]): Cypress.Chainable<string> {
  return cy
    .request({
      method: 'PUT',
      url: `${PLATFORM}${path}`,
      headers: { 'Content-Type': 'text/turtle' },
      body: `<> a ${types.map((type) => `<${type}>`).join(', ')} ; <http://purl.org/dc/terms/title> "${title}" .`,
    })
    .then(() => path);
}

describe('OAI-PMH', () => {
  it('identifies the repository and its metadata formats', () => {
    oai({ verb: 'Identify' }).then((document) => {
      expect(texts(document, NS.oai, 'baseURL')).to.deep.eq([`${Cypress.config('baseUrl')}${OAI}`]);
      expect(texts(document, NS.oai, 'protocolVersion')).to.deep.eq(['2.0']);
      expect(texts(document, NS.oai, 'granularity')).to.deep.eq(['YYYY-MM-DDThh:mm:ssZ']);
      expect(texts(document, NS.oai, 'deletedRecord')).to.deep.eq(['no']);
    });
    oai({ verb: 'ListMetadataFormats' }).then((document) => {
      expect(texts(document, NS.oai, 'metadataPrefix')).to.deep.eq(['oai_dc']);
    });
  });

  it('harvests an item built in a collection, with its community and collection as sets', () => {
    const community = `communities/${unique('oai-arts')}`;
    const collection = `${community}/${unique('paintings')}`;
    const communitySpec = community.substring('communities/'.length);
    const collectionSpec = `${communitySpec}:${collection.substring(community.length + 1)}`;
    createTyped(community, 'OAI arts', ['https://articulorium.org/ns/repository#Community']);
    createTyped(collection, 'OAI paintings', [
      'https://articulorium.org/ns/repository#Collection',
      'http://pcdm.org/models#Collection',
    ]);
    cy.request({
      method: 'POST',
      url: '/topology/topologies/book/instances',
      body: { parent: collection, title: 'Harvested Atlas', counts: { page: 2 } },
    }).then((built) => {
      const book = built.body.path as string;
      const page = `${book}/members/page-1`;

      oai({ verb: 'GetRecord', metadataPrefix: 'oai_dc', identifier: `${ID}${book}` }).then((document) => {
        expect(errorCode(document)).to.eq(null);
        expect(texts(document, NS.oai, 'setSpec')).to.deep.eq([communitySpec, collectionSpec]);
        expect(texts(document, NS.dc, 'title')).to.deep.eq(['Harvested Atlas']);
        expect(texts(document, NS.dc, 'identifier')).to.include.members([
          `${Cypress.config('baseUrl')}/ui/browse/${book}`,
          `${Cypress.config('baseUrl')}${PLATFORM}${book}`,
        ]);
        expect(texts(document, NS.oai, 'datestamp')[0]).to.match(/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ$/);
      });

      // the collection's set holds the book but not its pages, which are records of their own only
      // with articulorium.oai.include-nested
      oai({ verb: 'ListIdentifiers', metadataPrefix: 'oai_dc', set: collectionSpec }).then((document) => {
        expect(texts(document, NS.oai, 'identifier')).to.deep.eq([`${ID}${book}`]);
      });
      oai({ verb: 'GetRecord', metadataPrefix: 'oai_dc', identifier: `${ID}${page}` })
        .then((document) => expect(errorCode(document)).to.eq('idDoesNotExist'));

      // harvesting everything changed since today reaches the book, following resumption tokens
      const today = new Date().toISOString().substring(0, 10);
      harvest({ verb: 'ListRecords', metadataPrefix: 'oai_dc', from: today }, 'identifier', `${ID}${book}`)
        .should('include', `${ID}${book}`);
      harvest({ verb: 'ListSets' }, 'setSpec', collectionSpec).should('include.members', [communitySpec, collectionSpec]);
    });
  });

  it('answers protocol errors as OAI-PMH documents', () => {
    oai({}).then((document) => expect(errorCode(document)).to.eq('badVerb'));
    oai({ verb: 'ListRecords' }).then((document) => expect(errorCode(document)).to.eq('badArgument'));
    oai({ verb: 'ListRecords', metadataPrefix: 'mods' })
      .then((document) => expect(errorCode(document)).to.eq('cannotDisseminateFormat'));
    oai({ verb: 'ListRecords', resumptionToken: 'expired' })
      .then((document) => expect(errorCode(document)).to.eq('badResumptionToken'));
    oai({ verb: 'GetRecord', metadataPrefix: 'oai_dc', identifier: `${ID}${unique('missing')}` })
      .then((document) => expect(errorCode(document)).to.eq('idDoesNotExist'));
  });
});
