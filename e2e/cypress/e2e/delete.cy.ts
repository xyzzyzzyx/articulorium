describe('Deleting', () => {
  it('keeps the resource when the confirmation is cancelled', () => {
    cy.createContainer('Kept').then((name) => {
      cy.visitUi(`browse/${name}`);
      cy.get('button.ui-danger').click();
      cy.get('mat-dialog-container').should('contain.text', 'This cannot be undone').contains('button', 'Cancel').click();
      cy.get('mat-dialog-container').should('not.exist');
      cy.get('h1').should('have.text', 'Kept');
      cy.request(`/platform/${name}`).its('status').should('eq', 200);
    });
  });

  it('deletes a binary and returns to its container', () => {
    cy.createContainer('Parent').then((parent) => {
      cy.createBinary(parent, { name: 'gone.txt', type: 'text/plain', content: 'bye' }).then((path) => {
        cy.visitUi(`browse/${path}`);
        cy.get('button.ui-danger').click();
        cy.get('mat-dialog-container').contains('button', 'Delete').click();
        cy.notified('Resource deleted');
        cy.location('pathname').should('eq', `/ui/browse/${parent}`);
        cy.get('.ui-badge').should('have.text', '0');
        cy.request({ url: `/platform/${path}`, failOnStatusCode: false }).its('status').should('eq', 404);
      });
    });
  });

  it('deletes containers recursively', () => {
    cy.createContainer('Tree').then((name) => {
      cy.request({ method: 'PUT', url: `/platform/${name}/child`, headers: { 'Content-Type': 'text/turtle' }, body: '' });
      cy.visitUi(`browse/${name}`);
      cy.get('button.ui-danger').click();
      cy.get('mat-dialog-container').should('contain.text', 'everything it contains').contains('button', 'Delete').click();
      cy.location('pathname').should('eq', '/ui/browse');
      cy.request({ url: `/platform/${name}/child`, failOnStatusCode: false }).its('status').should('eq', 404);
    });
  });
});
