describe('Versions', () => {
  it('lists every content change of a binary and shows historic versions', () => {
    cy.createContainer('History').then((parent) => {
      cy.createBinary(parent, { name: 'notes.txt', type: 'text/plain', content: 'first version\n' }).then((path) => {
        cy.request({ method: 'PUT', url: `/platform/${path}`, headers: { 'Content-Type': 'text/plain' }, body: 'second version\n' });
        cy.visitUi(`browse/${path}`);
        cy.get('aside').contains('a', 'Versions').click();
        cy.location('pathname').should('eq', `/ui/versions/${path}`);
        cy.get('h1').should('have.text', 'Versions');
        // newest first
        cy.get('table tbody tr').should('have.length', 2);
        cy.get('table tbody tr').first().should('contain.text', 'v2');
        cy.get('table tbody tr').last().should('contain.text', 'v1');
        cy.get('table tbody tr').last().find('a[download]').should('exist');

        cy.contains('a', /^\s*v1\s*$/).click();
        cy.location('pathname').should('eq', `/ui/memento/v1/${path}`);
        cy.get('.ui-banner').should('contain.text', 'historic version').and('contain.text', 'v1');
        cy.get('ui-breadcrumb').should('contain.text', 'Version v1');
        cy.contains('a', 'Download content')
          .invoke('attr', 'href')
          .then((href) => cy.request(String(href)).its('body').should('eq', 'first version\n'));
        cy.contains('a', 'Current version').click();
        cy.location('pathname').should('eq', `/ui/browse/${path}`);
      });
    });
  });

  it('records versions of containers on request', () => {
    cy.createContainer('Snapshot').then((name) => {
      cy.visitUi(`browse/${name}`);
      cy.contains('button', 'Create version').click();
      cy.notified('Version created');
      cy.location('pathname').should('eq', `/ui/versions/${name}`);
      cy.get('table tbody tr').should('have.length', 1);
      cy.contains('button', 'Create version').click();
      cy.get('table tbody tr').should('have.length', 2);

      cy.request({
        method: 'PATCH',
        url: `/platform/${name}`,
        headers: { 'Content-Type': 'application/sparql-update' },
        body: 'PREFIX dcterms: <http://purl.org/dc/terms/> DELETE { <> dcterms:title ?t } INSERT { <> dcterms:title "Changed" } WHERE { <> dcterms:title ?t }',
      });
      cy.contains('a', /^\s*v1\s*$/).click();
      cy.get('ui-property-table').should('contain.text', 'Snapshot').and('not.contain.text', 'Changed');
      cy.contains('a', 'Download content').should('not.exist');
    });
  });

  it('does not version the root container', () => {
    cy.visitUi('browse');
    cy.get('h1').should('have.text', 'Root');
    cy.contains('button', 'Create version').should('not.exist');
    cy.get('aside').contains('a', 'Versions').should('not.exist');
  });

  it('explains an empty version list', () => {
    cy.createContainer('Unversioned').then((name) => {
      cy.visitUi(`versions/${name}`);
      cy.contains('No versions yet');
    });
  });
});
