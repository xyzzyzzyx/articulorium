import { PLATFORM, pathOf, unique } from '../support/e2e';

const ARCHIVAL_GROUP = 'http://fedora.info/definitions/v4/repository#ArchivalGroup';

describe('Archival groups', () => {
  it('creates an archival group from the create form', () => {
    const name = unique('ag');
    cy.visitUi('browse');
    cy.get('ui-create-child-form').within(() => {
      cy.get('input[name=slug]').type(name);
      cy.get('textarea[name=turtle]').clear().type('<> <http://purl.org/dc/terms/title> "Ship log" .');
      cy.get('.ui-archival-group input').check();
      cy.contains('button', 'Add').click();
    });
    cy.notified('Resource created');
    cy.location('pathname').should('eq', `/ui/browse/${name}`);
    cy.get('.ui-archival-group-badge').should('contain.text', 'Archival group');
    cy.get('mat-chip').should('contain.text', 'fedora:ArchivalGroup');
    // archival groups are versioned from the start, and members cannot be nested groups
    cy.request({ url: `${PLATFORM}${name}/fcr:versions`, headers: { Accept: 'application/json' } })
      .its('body.versions')
      .should('have.length', 1);
    cy.get('ui-create-child-form .ui-archival-group').should('not.exist');
  });

  it('versions members through their group', () => {
    const name = unique('ag');
    cy.request({
      method: 'PUT',
      url: `${PLATFORM}${name}`,
      headers: { 'Content-Type': 'text/turtle', Link: `<${ARCHIVAL_GROUP}>; rel="type"` },
      body: '<> <http://purl.org/dc/terms/title> "Voyage" .',
    });
    cy.request({
      method: 'POST',
      url: `${PLATFORM}${name}`,
      headers: { 'Content-Type': 'text/plain', Slug: 'day-1.txt' },
      body: 'Calm seas',
    }).then((response) => {
      const member = pathOf(String(response.headers['location']));
      cy.visitUi(`browse/${member}`);
      cy.get('.ui-archival-member-badge').should('contain.text', 'Part of an archival group');
      cy.get('.ui-archival-versioning').should('contain.text', 'versioning the archival group');
      cy.get('aside').contains('button', 'Create version').should('not.exist');
      cy.request({ method: 'POST', url: `${PLATFORM}${member}/fcr:versions`, failOnStatusCode: false })
        .its('status')
        .should('eq', 405);

      cy.get('.ui-archival-member-badge').click();
      cy.location('pathname').should('eq', `/ui/browse/${name}`);
      cy.get('aside').contains('button', 'Create version').click();
      cy.notified('Version created');
      cy.get('table tbody tr').should('have.length', 3);
    });
  });
});
