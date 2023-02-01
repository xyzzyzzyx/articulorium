import { unique } from '../support/e2e';

describe('Topologies', () => {
  it('lists the built-in topologies and shows them read-only', () => {
    cy.visitUi('topologies');
    for (const name of ['Book', 'Newspaper', 'Journal', 'Postcards', 'Manuscript', 'Audiovisual recording']) {
      cy.get('.ui-topology-list').should('contain.text', name);
    }
    cy.get('.ui-topology-list').contains('a', 'Newspaper').click();
    cy.location('pathname').should('eq', '/ui/topologies/newspaper');
    cy.contains('Built-in (read-only)');
    cy.get('ui-node-editor [data-node=issue]').should('exist');
    cy.get('ui-node-editor [data-node=page]').should('exist');
    cy.contains('button', 'Save').should('not.exist');
  });

  it('duplicates a topology, changes and deletes the copy', () => {
    const id = unique('album');
    cy.visitUi('topologies/postcards');
    cy.contains('a', 'Duplicate').click();
    cy.location('pathname').should('eq', '/ui/topologies/new');
    cy.get('input[name=id]').clear().type(id);
    cy.get('input[name=name]').clear().type('Photo album');
    // the root's "Child level" button follows the editors of its children
    cy.get('.ui-add-child').last().click();
    cy.contains('button', 'Save').click();
    cy.notified('Topology saved');
    cy.location('pathname').should('eq', `/ui/topologies/${id}`);
    cy.get('h1').should('have.text', 'Photo album');
    cy.request(`/topology/topologies/${id}`).its('body.root.children').should('have.length', 2);

    cy.visitUi('topologies');
    cy.get('.ui-topology-list').contains('a', 'Photo album').click();
    cy.contains('button', 'Delete').click();
    cy.notified('Topology deleted');
    cy.request({ url: `/topology/topologies/${id}`, failOnStatusCode: false }).its('status').should('eq', 404);
  });

  it('builds a topology in any container from the topology aside', () => {
    cy.createContainer('Shelf').then((shelf) => {
      cy.visitUi(`browse/${shelf}`);
      cy.get('.ui-topology-start').click();
      cy.chooseOption('.ui-topology-build mat-select[name=topology]', 'Postcards');
      cy.get('.ui-topology-build input[name=title]').type('Seaside cards');
      cy.get('.ui-topology-build input[name=count-postcard]').clear().type('1');
      cy.get('.ui-topology-build').contains('button', 'Build').click();
      cy.notified(/Built \d+ resources/);
      cy.get('.ui-topology-members a').should('have.length', 1).first().click();
      cy.get('.ui-topology-members a').then(($links) => {
        expect([...$links].map((link) => link.textContent)).to.deep.equal(['back', 'front']);
      });
    });
  });
});
