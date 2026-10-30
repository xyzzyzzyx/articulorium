describe('Search', () => {
  it('finds resources by path and media type and keeps the search in the URL', () => {
    cy.createContainer('Searchable').then((parent) => {
      cy.createBinary(parent, { name: 'table.csv', type: 'text/csv', content: 'a,b\n' });
      cy.createBinary(parent, { name: 'notes.txt', type: 'text/plain', content: 'text' });
      cy.visitUi('search');
      cy.chooseOption('.ui-condition mat-select:first', 'mime_type');
      cy.get('input[name=value0]').type('text/*');
      cy.contains('button', 'Add condition').click();
      cy.get('input[name=value1]').type(`${parent}/*`);
      cy.contains('button', /Search\s*$/).click();
      cy.location('search').should('contain', 'condition=mime_type').and('contain', 'max_results=20');
      cy.contains('2 result(s)');
      cy.get('table').should('contain.text', 'table.csv').and('contain.text', 'notes.txt');

      // the URL alone reproduces the search
      cy.location('href').then((href) => cy.visit(href));
      cy.contains('2 result(s)');
      cy.get('input[name=value0]').should('have.value', 'text/*');

      cy.get('input[name=value0]').clear().type('text/csv');
      cy.contains('button', /Search\s*$/).click();
      cy.contains('1 result(s)');
      cy.get('table').contains('a', 'table.csv').click();
      cy.location('pathname').should('eq', `/ui/browse/${parent}/table.csv`);
    });
  });

  it('removes conditions', () => {
    cy.visitUi('search');
    cy.contains('button', 'Add condition').click();
    cy.get('.ui-condition input').should('have.length', 2);
    cy.get('button[aria-label="Remove condition"]').first().click();
    cy.get('.ui-condition input').should('have.length', 1);
    cy.get('button[aria-label="Remove condition"]').should('be.disabled');
  });

  it('orders and pages results', () => {
    cy.createContainer('Paged').then((parent) => {
      ['a', 'b', 'c'].forEach((name) => cy.createBinary(parent, { name: `${name}.txt`, type: 'text/plain', content: name }));
      cy.visitUi(`search?condition=fedora_id%3D${parent}%2F*&max_results=2&order_by=fedora_id&order=desc`);
      cy.contains('3 result(s)');
      cy.get('table tbody tr').should('have.length', 2);
      cy.get('table tbody tr').first().should('contain.text', 'c.txt');
      cy.get('mat-paginator').should('contain.text', '1 – 2 of 3');
      cy.get('button[aria-label="Next page"]').click();
      cy.location('search').should('contain', 'offset=2');
      cy.get('table tbody tr').should('have.length', 1).and('contain.text', 'a.txt');
    });
  });
});
