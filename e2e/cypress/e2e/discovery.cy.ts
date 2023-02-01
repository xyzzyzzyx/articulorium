import { PLATFORM, unique } from '../support/e2e';

// The public discovery layer (/discover/): server-rendered pages for search engines, search with
// facets over the Solr collection, resource pages presenting IIIF exports in Mirador, and the
// deployment configuration served by the discovery service (/discover/api/).

/** A 3 x 2 pixel PNG. */
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAEklEQVR4nGP4z8AARAwM/xkYAB0EA/2d8F0yAAAAAElFTkSuQmCC';

const ART = 'https://articulorium.org/ns/repository#';
const DCTERMS = 'http://purl.org/dc/terms/';

/** How long indexing may take, in attempts one second apart. */
const ATTEMPTS = 40;

/** Creates a community or a collection below a container; yields its path. */
function createStructure(parent: string, kind: 'Community' | 'Collection', title: string): Cypress.Chainable<string> {
  const types = kind === 'Community' ? `<${ART}Community>` : `<${ART}Collection>, <http://pcdm.org/models#Collection>`;
  return cy
    .request({
      method: 'POST',
      url: `${PLATFORM}${parent}`,
      headers: { 'Content-Type': 'text/turtle', Slug: title.toLowerCase().replace(/[^a-z0-9]+/g, '-') },
      body: `<> a ${types} ; <${DCTERMS}title> "${title}" ; <${DCTERMS}description> "About ${title}" .`,
    })
    .then((response) => new URL(response.headers['location'] as string).pathname.substring(PLATFORM.length));
}

/** Re-runs a discovery search until it lists a title (indexing is asynchronous). */
function searchUntilFound(title: string, attempt = 1): Cypress.Chainable<{ path: string; title: string }> {
  return cy.request({ url: '/discover/api/search', qs: { q: title, lang: 'en' } }).then((response) => {
    const hit = (response.body.results as { path: string; title: string }[]).find((result) => result.title === title);
    if (hit) {
      return cy.wrap(hit, { log: false });
    }
    if (attempt >= ATTEMPTS) {
      throw new Error(`"${title}" was not found by the discovery search`);
    }
    return cy.wait(1000, { log: false }).then(() => searchUntilFound(title, attempt + 1));
  });
}

describe('Discovery layer', () => {
  const community = unique('Discovery library');
  const collection = unique('Discovery maps');
  const book = unique('Discovery atlas');
  let bookPath: string;
  let collectionPath: string;

  before(() => {
    createStructure('communities', 'Community', community).then((communityPath) =>
      createStructure(communityPath, 'Collection', collection).then((path) => {
        collectionPath = path;
        return cy
          .request({
            method: 'POST',
            url: '/topology/topologies/book/instances',
            body: { parent: path, title: book, counts: { page: 1 } },
          })
          .then((built) => {
            bookPath = built.body.path as string;
            // a page image, uploaded through the user interface's topology aside (records its PCDM use)
            cy.visit(`/ui/browse/${bookPath}/members/page-1`);
            cy.get('input[type=file][aria-label="Upload Master image"]').selectFile({
              contents: Cypress.Buffer.from(PNG, 'base64'),
              fileName: 'master.png',
              mimeType: 'image/png',
            });
            cy.notified('Master image uploaded');
          });
      }),
    );
    searchUntilFound(book);
  });

  it('serves the public part of the deployment configuration', () => {
    cy.request('/discover/api/config').then((response) => {
      expect(response.body.site.name).to.have.property('value');
      expect(response.body.locales.defaultLocale).to.eq('en');
      expect(response.body.search.facets.map((facet: { id: string }) => facet.id)).to.include('type');
      expect(response.body.urls.publicUrl).to.eq(`${Cypress.config('baseUrl')}/discover/`);
      // internal addresses and Solr details stay in the service
      expect(JSON.stringify(response.body)).not.to.contain('solr').and.not.to.contain('type_str');
    });
  });

  it('sends visitors to the home page in their language', () => {
    cy.request({ url: '/discover/', headers: { 'Accept-Language': 'de-AT, en;q=0.5' }, followRedirect: false }).then((response) => {
      expect(response.status).to.eq(302);
      expect(response.headers['location']).to.match(/\/discover\/de\/?$/);
    });
  });

  it('renders resource pages on the server with metadata for search engines', () => {
    cy.request({ url: `/discover/en/items/${bookPath}`, headers: { Accept: 'text/html' } }).then((response) => {
      const html = response.body as string;
      const url = `${Cypress.config('baseUrl')}/discover/en/items/${bookPath}`;
      expect(html).to.contain('<html lang="en" dir="ltr"');
      expect(html).to.match(new RegExp(`<h1[^>]*>${Cypress._.escapeRegExp(book)}</h1>`));
      expect(html).to.contain(`<title>${book} | `);
      expect(html).to.contain(`<link rel="canonical" href="${url}">`);
      expect(html).to.contain('hreflang="x-default"');
      expect(html).to.contain('hreflang="ar"');
      expect(html).to.contain(`<meta property="og:title" content="${book}">`);
      expect(html).to.contain('<meta name="robots" content="index, follow">');
      expect(html).to.match(/<script id="disc-structured-data" type="application\/ld\+json">.*"@type":"Book"/);
      // the trail of the community structure
      expect(html).to.contain(collection);
    });
  });

  it('answers missing resources with 404', () => {
    cy.request({ url: `/discover/en/items/${collectionPath}/no-such-thing`, failOnStatusCode: false }).then((response) => {
      expect(response.status).to.eq(404);
      expect(response.body).to.contain('<meta name="robots" content="noindex, follow">');
    });
  });

  it('searches, refines with facets and opens a resource in Mirador', () => {
    cy.visit('/discover/en/search');
    cy.get('h1').should('have.text', 'Browse the collections');
    cy.get('main input[type=search]').type(`${book}{enter}`);
    cy.location('search').should('contain', 'q=');
    cy.get('#disc-results-heading').should('contain.text', 'result');
    cy.get('disc-facet-panel').contains('a', 'Book').click();
    cy.location('search').should('contain', 'f=type');
    cy.get('.disc-active').should('contain.text', 'Type: Book');
    cy.get('.disc-result-list').contains('a', book).click();
    cy.location('pathname').should('eq', `/discover/en/items/${bookPath}`);
    cy.get('h1').should('have.text', book);
    cy.get('.disc-breadcrumb').should('contain.text', collection).and('contain.text', community);
    // Mirador presents the IIIF Manifest (deep zoom images)
    cy.get('disc-mirador-viewer .mirador-viewer', { timeout: 20000 }).should('exist');
    cy.get('disc-mirador-viewer .openseadragon-canvas', { timeout: 20000 }).should('exist');
    cy.get('.disc-item-links a').should('have.attr', 'href', `${Cypress.config('baseUrl')}/transform/iiif/${bookPath}`);
  });

  it('lists the members of collections', () => {
    cy.visit(`/discover/en/items/${collectionPath}`);
    cy.get('#disc-members-heading').should('have.text', 'In this collection');
    cy.get('disc-resource-card').contains('a', book).should('have.attr', 'href', `/discover/en/items/${bookPath}`);
  });

  it('switches language to another URL of the same page', () => {
    cy.visit(`/discover/en/items/${bookPath}`);
    cy.get('disc-language-menu button').click();
    cy.get('.mat-mdc-menu-panel').contains('a', 'العربية').click();
    cy.location('pathname').should('eq', `/discover/ar/items/${bookPath}`);
    cy.get('html').should('have.attr', 'lang', 'ar').and('have.attr', 'dir', 'rtl');
    cy.get('.disc-breadcrumb').should('contain.text', 'الرئيسية');
  });

  it('offers robots.txt and a sitemap of public resources', () => {
    cy.request('/discover/robots.txt').its('body').should('contain', 'Disallow: /discover/api/').and('contain', 'Sitemap:');
    cy.request('/discover/sitemap.xml').then((response) => {
      expect(response.headers['content-type']).to.contain('application/xml');
      expect(response.body).to.contain('<urlset');
    });
  });
});
