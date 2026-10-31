import { unique } from '../support/e2e';

describe('Navigation', () => {
  it('opens the root container from the gateway root', () => {
    cy.visit('/');
    cy.location('pathname').should('eq', '/ui/browse');
    cy.get('h1').should('have.text', 'Root');
    cy.get('ui-breadcrumb').should('contain.text', 'Root');
    cy.get('ui-resource-page').should('contain.text', 'Children');
  });

  it('serves /ui without a trailing slash with the right base', () => {
    cy.visit('/ui');
    cy.location('pathname').should('eq', '/ui/browse');
    cy.get('base').should('have.attr', 'href', '/ui/');
  });

  it('mints resource IRIs under the gateway address', () => {
    const base = `${Cypress.config('baseUrl')}/platform/`;
    cy.request({
      method: 'POST',
      url: '/platform/',
      headers: { 'Content-Type': 'text/turtle' },
      body: '<> <http://purl.org/dc/terms/title> "Located" .',
    }).then((response) => {
      expect(response.headers['location'], 'Location of the new resource').to.match(new RegExp(`^${Cypress._.escapeRegExp(base)}`));
    });
    cy.request({ url: '/platform/', headers: { Accept: 'application/n-triples' } })
      .its('body')
      .should('contain', `<${base}>`);
  });

  it('switches between the main views', () => {
    cy.visitUi('browse');
    cy.get('.ui-nav').contains('a', 'Search').click();
    cy.location('pathname').should('eq', '/ui/search');
    cy.get('h1').should('have.text', 'Search');
    cy.get('.ui-nav').contains('a', 'About').click();
    cy.get('h1').should('have.text', 'About Articulorum');
    // the API link opens the gateway's Swagger UI, which lists every service's OpenAPI description
    cy.get('.ui-nav')
      .contains('a', 'API')
      .should('have.attr', 'href')
      .and('match', /^http:\/\/localhost:8000\/swagger-ui\.html$/)
      .then((href) => cy.request(String(href)).its('status').should('eq', 200));
    cy.get('.ui-nav').contains('a', 'Communities').click();
    cy.location('pathname').should('eq', '/ui/communities');
    cy.get('.ui-nav').contains('a', 'Topologies').click();
    cy.location('pathname').should('eq', '/ui/topologies');
    cy.get('.ui-brand').click();
    cy.location('pathname').should('eq', '/ui/browse');
  });

  it('navigates through children and breadcrumbs', () => {
    cy.createContainer('Outer').then((outer) => {
      cy.request({
        method: 'PUT',
        url: `/platform/${outer}/inner`,
        headers: { 'Content-Type': 'text/turtle' },
        body: '<> <http://purl.org/dc/terms/title> "Inner" .',
      });
      cy.visitUi(`browse/${outer}`);
      cy.get('h1').should('have.text', 'Outer');
      cy.get('.ui-children').contains('a', 'inner').click();
      cy.location('pathname').should('eq', `/ui/browse/${outer}/inner`);
      cy.get('h1').should('have.text', 'Inner');
      cy.get('ui-breadcrumb').contains('a', outer).click();
      cy.get('h1').should('have.text', 'Outer');
      cy.get('ui-breadcrumb').contains('a', 'Root').click();
      cy.get('h1').should('have.text', 'Root');
    });
  });

  it('opens deep links to resources named like files', () => {
    cy.createContainer('Files').then((parent) => {
      cy.createBinary(parent, { name: 'readme.md', type: 'text/markdown', content: '# hello' }).then((path) => {
        cy.visitUi(`browse/${path}`);
        cy.get('h1').should('have.text', 'readme.md');
      });
    });
  });

  it('reports missing resources', { expose: { allowConsoleErrors: true } }, () => {
    cy.visitUi(`browse/${unique('missing')}`);
    cy.get('.ui-error').should('be.visible').and('contain.text', 'Retry');
    cy.get('.ui-error').contains('a', 'Go to root').click();
    cy.get('h1').should('have.text', 'Root');
  });
});
