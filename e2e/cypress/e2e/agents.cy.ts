// The service's agents come from the agent Spring Boot starter (articulorium.agents.definitions in
// service/src/main/resources/application.yml); these checks go through the gateway like the UI.
describe('Agents', () => {
  it('lists the configured agents', () => {
    cy.request('/ui/api/agents').then((response) => {
      expect(response.status).to.eq(200);
      expect(response.body).to.deep.include({ name: 'owl', description: 'read owl' });
    });
  });

  it('looks one agent up by name', () => {
    cy.request('/ui/api/agents/owl').its('body').should('deep.equal', { name: 'owl', description: 'read owl' });
  });

  it('answers 404 for an unknown agent', () => {
    cy.request({ url: '/ui/api/agents/no-such-agent', failOnStatusCode: false }).its('status').should('eq', 404);
  });
});
