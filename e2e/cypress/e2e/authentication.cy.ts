import { PLATFORM, unique } from '../support/e2e';

/** Signs in on Keycloak's login page (the stack's OpenID Connect provider, on the gateway's origin). */
function signInAs(username: string, password: string): void {
  cy.get('.ui-account').contains('button', 'Sign in').click();
  cy.location('pathname').should('eq', '/auth/realms/articulorium/protocol/openid-connect/auth');
  cy.location('search').should('contain', 'code_challenge_method=S256').and('contain', 'client_id=articulorium-ui');
  cy.get('#username').type(username);
  cy.get('#password').type(password, { log: false });
  cy.get('#kc-login').click();
}

describe('Authentication', () => {
  it('signs in with the OpenID Connect provider, records the agent and signs out', () => {
    cy.createContainer('Signed in').then((parent) => {
      cy.visitUi(`browse/${parent}`);
      signInAs('curator', 'curator');

      // back on the page sign-in started from, without the authorization response in the address
      cy.location('pathname').should('eq', `/ui/browse/${parent}`);
      cy.location('search').should('eq', '');
      cy.get('.ui-agent').should('contain.text', 'curator').and('have.attr', 'title', 'info:oidc/agent/curator');

      // requests now carry the access token: the platform records the agent as creator
      const child = unique('signed');
      cy.get('ui-create-child-form').within(() => {
        cy.get('input[name=slug]').type(child);
        cy.contains('button', 'Add').click();
      });
      cy.notified('Resource created');
      cy.request({ url: `${PLATFORM}${parent}/${child}`, headers: { Accept: 'application/n-triples' } })
        .its('body')
        .should('contain', 'info:oidc/agent/curator');

      // the session survives a reload of the tab
      cy.reload();
      cy.get('.ui-agent').should('contain.text', 'curator');

      cy.get('.ui-account').contains('button', 'Sign out').click();
      cy.location('pathname').should('match', /^\/ui\//);
      cy.get('.ui-account').should('contain.text', 'Sign in').and('not.contain.text', 'curator');
    });
  });

  it('rejects bearer tokens the identity provider did not issue', () => {
    cy.request({
      url: `${PLATFORM}`,
      headers: { Authorization: 'Bearer eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiJtYWxsb3J5In0.c2lnbmF0dXJl' },
      failOnStatusCode: false,
    }).then((response) => {
      expect(response.status).to.eq(401);
      expect(response.headers['www-authenticate']).to.contain('Bearer');
    });
  });

  it('publishes the OpenID Connect configuration on the gateway', () => {
    cy.request('/auth/realms/articulorium/.well-known/openid-configuration')
      .its('body')
      .should((configuration: { issuer: string; code_challenge_methods_supported: string[] }) => {
        expect(configuration.issuer).to.eq(`${Cypress.config('baseUrl')}/auth/realms/articulorium`);
        expect(configuration.code_challenge_methods_supported).to.include('S256');
      });
  });
});
