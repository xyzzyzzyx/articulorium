import { PLATFORM, unique } from '../support/e2e';

describe('Communities', () => {
  it('builds a community structure and an item from a topology', () => {
    const community = unique('Library');
    cy.visitUi('communities');
    cy.get('h1').should('have.text', 'Communities');
    cy.get('.ui-new-community input[name=communityTitle]').type(community);
    cy.get('.ui-new-community textarea[name=communityDescription]').type('All library holdings');
    cy.get('.ui-new-community').contains('button', 'Create').click();
    cy.notified('Community created');
    cy.get('.ui-communities-list').contains('a', community).click();
    cy.get('h1').should('have.text', community);
    cy.get('.ui-structure-kind').should('have.text', 'Community');

    cy.get('.ui-new-community input[name=communityTitle]').type('Special collections');
    cy.get('.ui-new-community').contains('button', 'Create').click();
    cy.notified('Sub-community created');
    cy.get('.ui-communities-list').should('contain.text', 'Special collections');

    cy.get('.ui-new-collection input[name=collectionTitle]').type('Rare books');
    cy.get('.ui-new-collection').contains('button', 'Create').click();
    cy.notified('Collection created');
    cy.get('.ui-collections-list').contains('a', 'Rare books').click();
    cy.get('.ui-structure-kind').should('have.text', 'Collection');
    cy.get('.ui-items-list').should('contain.text', 'None yet');

    // build a book with two pages in the collection
    cy.chooseOption('.ui-topology-build mat-select[name=topology]', 'Book');
    cy.get('.ui-topology-build input[name=title]').type('Moby Dick');
    cy.get('.ui-topology-build input[name=count-page]').clear().type('2');
    // Synchronize with the asynchronous topology creation request
    cy.intercept('POST', '**/topology/topologies/book/instances').as('buildBook');
    cy.get('.ui-topology-build').contains('button', 'Build').click();
    // Wait for the server to finish processing the request
    cy.wait('@buildBook').its('response.statusCode').should('be.oneOf', [200, 201]);
    // Assert the notification after the operation completes
    cy.notified(/Built \d+ resources/);
    cy.location('pathname').should('match', /^\/ui\/browse\/communities\/.+\/moby-dick$/);
    cy.get('h1').should('have.text', 'Moby Dick');
    cy.get('mat-chip').should('contain.text', 'pcdm:Object').and('contain.text', 'bibo:Book');
    cy.get('.ui-topology-members a').should('have.length', 2);

    // the topology aside adds pages and edits the node's metadata
    cy.get('.ui-topology').contains('button', 'Add Page').click();
    cy.notified('Page added');
    cy.location('pathname').should('match', /\/members\/page-3$/);
    cy.get('.ui-topology').contains('a', 'Parent').click();
    cy.get('.ui-topology-members a').should('have.length', 3);
    cy.get('.ui-topology-metadata textarea[name="http://purl.org/dc/terms/creator"]').type('Herman Melville');
    cy.get('.ui-topology-metadata').contains('button', 'Save metadata').click();
    cy.notified('Metadata saved');
    cy.get('ui-property-table').first().should('contain.text', 'Herman Melville');

    // the collection lists the item
    cy.visitUi('communities');
    cy.get('.ui-communities-list').contains('a', community).click();
    cy.get('.ui-collections-list').contains('a', 'Rare books').click();
    cy.get('.ui-items-list').should('contain.text', 'Moby Dick');
  });

  it('is stored as plain LDP resources', () => {
    cy.request({ url: `${PLATFORM}communities`, headers: { Accept: 'application/n-triples' } })
      .its('body')
      .should('contain', 'https://articulorum.org/ns/repository#CommunityRoot');
  });
});
