import { PLATFORM } from '../support/e2e';

describe('Permissions', () => {
  it('shows the effective access and grants and revokes access', () => {
    cy.createContainer('Guarded').then((name) => {
      cy.visitUi(`browse/${name}`);
      cy.get('.ui-access-badge').should('contain.text', 'read, write, append, control');
      cy.get('aside').contains('a', 'Permissions').click();
      cy.location('pathname').should('eq', `/ui/permissions/${name}`);
      cy.get('.ui-permissions-agent').should('contain.text', 'Anonymous');
      cy.get('.ui-permissions-source').should('contain.text', 'Repository default');
      // the inherited authorization is copied, so saving keeps everyone's access
      cy.get('.ui-authorization').should('have.length', 1).and('contain.text', 'Everyone');

      cy.chooseOption('.ui-grant-form mat-select[name=kind]', 'Group');
      cy.get('.ui-grant-form input[name=iri]').type('info:consul/role/curators');
      cy.get('.ui-grant-form').contains('label', 'write').click();
      cy.get('.ui-grant-form').contains('button', 'Add').click();
      cy.get('.ui-authorization').should('have.length', 2).last().should('contain.text', 'Group info:consul/role/curators');
      cy.contains('button', 'Save permissions').click();
      cy.notified('Permissions saved');
      cy.get('.ui-permissions-source').should('contain.text', "This resource's own access control list");
      cy.request({ url: `${PLATFORM}${name}/fcr:acl`, headers: { Accept: 'text/turtle' } })
        .its('body')
        .should('contain', 'info:consul/role/curators');

      cy.contains('button', 'Remove access control list').click();
      cy.notified('Access control list removed');
      cy.get('.ui-permissions-source').should('contain.text', 'Repository default');
      cy.request({ url: `${PLATFORM}${name}/fcr:acl`, failOnStatusCode: false }).its('status').should('eq', 404);
    });
  });

  it('enforces access control lists', () => {
    cy.createContainer('Read only').then((name) => {
      cy.request({
        method: 'PUT',
        url: `${PLATFORM}${name}/fcr:acl`,
        headers: { 'Content-Type': 'text/turtle' },
        body:
          '@prefix acl: <http://www.w3.org/ns/auth/acl#> . @prefix foaf: <http://xmlns.com/foaf/0.1/> . ' +
          `<#r> a acl:Authorization ; acl:agentClass foaf:Agent ; acl:accessTo <${Cypress.config('baseUrl')}${PLATFORM}${name}> ; acl:mode acl:Read .`,
      });
      cy.request({ method: 'POST', url: `${PLATFORM}${name}`, failOnStatusCode: false, body: '' })
        .its('status')
        .should('eq', 401);
      cy.visitUi(`browse/${name}`);
      cy.get('.ui-access-badge').should('contain.text', 'read');
      cy.get('ui-create-child-form').should('not.exist');
      cy.contains('button', 'Delete').should('not.exist');
      cy.get('aside').contains('a', 'Permissions').click();
      cy.contains('You need control access');
    });
  });
});
