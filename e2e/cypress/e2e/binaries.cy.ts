describe('Binaries', () => {
  it('uploads a file and shows its technical metadata', () => {
    cy.createContainer('Uploads').then((parent) => {
      cy.visitUi(`browse/${parent}`);
      cy.chooseOption('ui-create-child-form mat-select[name=kind]', 'Binary');
      cy.get('ui-create-child-form textarea[name=turtle]').should('not.exist');
      cy.get('ui-create-child-form input[type=file]').selectFile({
        contents: Cypress.Buffer.from('first version\n'),
        fileName: 'notes.txt',
        mimeType: 'text/plain',
      });
      cy.get('ui-create-child-form input[name=mimeType]').should('have.value', 'text/plain');
      cy.get('ui-create-child-form').contains('button', 'Add').click();
      cy.location('pathname').should('eq', `/ui/browse/${parent}/notes.txt`);
      cy.get('h1').should('have.text', 'notes.txt');
      cy.get('mat-chip').should('contain.text', 'ldp:NonRDFSource');
      cy.get('ui-binary-content')
        .should('contain.text', 'text/plain')
        .and('contain.text', '14 bytes')
        .and('contain.text', 'urn:sha-256:');
      // binaries cannot contain children
      cy.get('ui-create-child-form').should('not.exist');
    });
  });

  it('downloads the content', () => {
    cy.createContainer('Downloads').then((parent) => {
      cy.createBinary(parent, { name: 'data.csv', type: 'text/csv', content: 'a,b\n1,2\n' }).then((path) => {
        cy.visitUi(`browse/${path}`);
        cy.contains('a', 'Download content')
          .should('have.attr', 'download', 'data.csv')
          .invoke('attr', 'href')
          .then((href) => cy.request(String(href)).its('body').should('eq', 'a,b\n1,2\n'));
      });
    });
  });

  it('replaces the content', () => {
    cy.createContainer('Replace').then((parent) => {
      cy.createBinary(parent, { name: 'notes.txt', type: 'text/plain', content: 'short' }).then((path) => {
        cy.visitUi(`browse/${path}`);
        cy.get('ui-binary-content').should('contain.text', '5 bytes');
        cy.get('ui-binary-content input[type=file]').selectFile({
          contents: Cypress.Buffer.from('a longer second version'),
          fileName: 'notes.txt',
          mimeType: 'text/plain',
        });
        cy.get('ui-binary-content').contains('button', 'Update').click();
        cy.notified('Content updated');
        cy.get('ui-binary-content').should('contain.text', '23 bytes');
        cy.request(`/platform/${path}`).its('body').should('eq', 'a longer second version');
      });
    });
  });

  it('checks fixity', () => {
    cy.createContainer('Fixity').then((parent) => {
      cy.createBinary(parent, { name: 'f.txt', type: 'text/plain', content: 'fixed bytes' }).then((path) => {
        cy.visitUi(`browse/${path}`);
        cy.contains('a', 'Fixity').click();
        cy.location('pathname').should('eq', `/ui/fixity/${path}`);
        cy.get('.ui-status').should('contain.text', 'SUCCESS');
        cy.get('.ui-facts').should('contain.text', '11 bytes').and('contain.text', 'valid');
        cy.contains('button', 'Check again').click();
        cy.get('.ui-status').should('contain.text', 'SUCCESS');
        cy.contains('a', 'Back to resource').click();
        cy.location('pathname').should('eq', `/ui/browse/${path}`);
      });
    });
  });

  it('edits the description of a binary', () => {
    cy.createContainer('Describe').then((parent) => {
      cy.createBinary(parent, { name: 'img.bin', type: 'application/octet-stream', content: 'xyz' }).then((path) => {
        cy.visitUi(`browse/${path}`);
        cy.get('ui-sparql-update-form textarea')
          .clear()
          .type('INSERT { <> <http://purl.org/dc/terms/title> "A picture" } WHERE { }', {
            parseSpecialCharSequences: false,
          });
        cy.get('ui-sparql-update-form').contains('button', 'Update').click();
        cy.notified('Properties updated');
        cy.get('h1').should('have.text', 'A picture');
      });
    });
  });
});
