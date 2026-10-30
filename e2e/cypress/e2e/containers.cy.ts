import { unique } from '../support/e2e';

describe('Containers', () => {
  it('creates a basic container at a chosen identifier', () => {
    const name = unique('books');
    cy.visitUi('browse');
    cy.get('ui-create-child-form').within(() => {
      cy.get('textarea[name=turtle]').should('contain.value', 'dcterms:title');
      cy.get('input[name=slug]').type(name);
      cy.get('textarea[name=turtle]').clear().type('<> <http://purl.org/dc/terms/title> "Books" ;{enter} <http://purl.org/dc/terms/subject> "fiction" .');
      cy.contains('button', 'Add').click();
    });
    cy.notified('Resource created');
    cy.location('pathname').should('eq', `/ui/browse/${name}`);
    cy.get('h1').should('have.text', 'Books');
    cy.get('mat-chip').should('contain.text', 'ldp:BasicContainer');
    cy.get('ui-property-table').first().should('contain.text', 'dcterms:subject').and('contain.text', 'fiction');
    cy.get('.ui-facts').should('contain.text', 'Created').and('contain.text', 'Children');
  });

  it('lets the platform mint the identifier', () => {
    cy.visitUi('browse');
    cy.get('ui-create-child-form').contains('button', 'Add').click();
    cy.notified('Resource created');
    cy.location('pathname').should('match', /^\/ui\/browse\/[^/]+$/);
    cy.get('h1').should('have.text', 'Untitled');
  });

  it('refuses to overwrite an existing resource', { expose: { allowConsoleErrors: true } }, () => {
    cy.createContainer('Existing').then((name) => {
      cy.visitUi('browse');
      cy.get('ui-create-child-form').within(() => {
        cy.get('input[name=slug]').type(name);
        cy.contains('button', 'Add').click();
      });
      cy.notified(/Create failed/);
      cy.location('pathname').should('eq', '/ui/browse');
      cy.request(`/platform/${name}`).its('body').should('contain', 'Existing');
    });
  });

  it('rejects invalid Turtle with the platform message', { expose: { allowConsoleErrors: true } }, () => {
    cy.visitUi('browse');
    cy.get('ui-create-child-form').within(() => {
      cy.get('textarea[name=turtle]').clear().type('this is not turtle');
      cy.contains('button', 'Add').click();
    });
    cy.notified(/Create failed/);
  });

  it('prefills membership for direct and indirect containers', () => {
    cy.createContainer('Collection').then((parent) => {
      cy.visitUi(`browse/${parent}`);
      cy.chooseOption('ui-create-child-form mat-select[name=kind]', 'Direct container');
      cy.get('textarea[name=turtle]')
        .should('contain.value', 'ldp:membershipResource')
        .and('contain.value', 'ldp:hasMemberRelation pcdm:hasMember');
      cy.chooseOption('ui-create-child-form mat-select[name=kind]', 'Indirect container');
      cy.get('textarea[name=turtle]').should('contain.value', 'ldp:insertedContentRelation ore:proxyFor');
      cy.get('input[name=slug]').type('members');
      cy.get('ui-create-child-form').contains('button', 'Add').click();
      cy.location('pathname').should('eq', `/ui/browse/${parent}/members`);
      cy.get('mat-chip').should('contain.text', 'ldp:IndirectContainer');
    });
  });

  it('keeps edited Turtle when switching the kind', () => {
    cy.visitUi('browse');
    cy.get('textarea[name=turtle]').clear().type('<> <urn:p> "mine" .');
    cy.chooseOption('ui-create-child-form mat-select[name=kind]', 'Direct container');
    cy.get('textarea[name=turtle]').should('have.value', '<> <urn:p> "mine" .');
  });

  it('shows blank nodes as other resources', () => {
    const name = unique('described');
    cy.request({
      method: 'PUT',
      url: `/platform/${name}`,
      headers: { 'Content-Type': 'text/turtle' },
      body: '<> <http://purl.org/dc/terms/title> "Described" ; <http://purl.org/dc/terms/creator> [ <http://xmlns.com/foaf/0.1/name> "Herman Melville" ] .',
    });
    cy.visitUi(`browse/${name}`);
    cy.contains('h2', 'Other resources');
    cy.get('mat-expansion-panel').should('have.length', 1);
    cy.contains('mat-expansion-panel-header', 'Herman Melville').click();
    cy.get('mat-expansion-panel ui-property-table').should('contain.text', 'foaf:name');
  });
});
