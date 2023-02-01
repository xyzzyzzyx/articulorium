describe('Updating properties', () => {
  it('starts from the prefixes the resource uses', () => {
    cy.createContainer('Prefixed').then((name) => {
      cy.visitUi(`browse/${name}`);
      cy.get('ui-sparql-update-form textarea')
        .should('contain.value', 'PREFIX dcterms: <http://purl.org/dc/terms/>')
        .and('contain.value', 'DELETE { }')
        .and('contain.value', 'INSERT { }');
    });
  });

  it('applies SPARQL Update and refreshes the view', () => {
    cy.createContainer('Before').then((name) => {
      cy.visitUi(`browse/${name}`);
      cy.get('ui-sparql-update-form textarea')
        .clear()
        .type(
          'PREFIX dcterms: <http://purl.org/dc/terms/>\n' +
            'DELETE { <> dcterms:title ?t } INSERT { <> dcterms:title "After" ; dcterms:subject "whales" } WHERE { <> dcterms:title ?t }',
          { parseSpecialCharSequences: false },
        );
      cy.get('ui-sparql-update-form').contains('button', 'Update').click();
      cy.notified('Properties updated');
      cy.get('h1').should('have.text', 'After');
      cy.get('ui-property-table').first().should('contain.text', 'whales');
      cy.get('ui-sparql-update-form textarea').should('contain.value', 'DELETE { }');
    });
  });

  it('resets the editor', () => {
    cy.createContainer('Reset').then((name) => {
      cy.visitUi(`browse/${name}`);
      cy.get('ui-sparql-update-form textarea').clear().type('garbage');
      cy.get('ui-sparql-update-form').contains('button', 'Reset').click();
      cy.get('ui-sparql-update-form textarea').should('contain.value', 'WHERE { }');
    });
  });

  it('reports invalid updates and protected triples', { expose: { allowConsoleErrors: true } }, () => {
    cy.createContainer('Guarded').then((name) => {
      cy.visitUi(`browse/${name}`);
      cy.get('ui-sparql-update-form textarea').clear().type('not sparql');
      cy.get('ui-sparql-update-form').contains('button', 'Update').click();
      cy.notified(/Update failed/);
      cy.get('ui-sparql-update-form textarea')
        .clear()
        .type('INSERT { <> <http://www.w3.org/ns/ldp#contains> <urn:x> } WHERE { }', {
          parseSpecialCharSequences: false,
        });
      cy.get('ui-sparql-update-form').contains('button', 'Update').click();
      cy.notified(/Update failed/);
      cy.get('h1').should('have.text', 'Guarded');
    });
  });
});
