import { PLATFORM, unique } from '../support/e2e';

// The platform's own HTML view of a resource: a long object must not squash the subject and
// predicate columns, which stay on one line and keep their full IRI in a tooltip.
describe('Platform HTML view', () => {
  it('keeps subject and predicate on one line next to a long object', () => {
    const name = unique('long');
    const text = 'lorem ipsum dolor sit amet '.repeat(200);
    cy.request({
      method: 'PUT',
      url: `${PLATFORM}${name}`,
      headers: { 'Content-Type': 'text/turtle' },
      body: `<> <http://purl.org/dc/terms/description> "${text}" .`,
    });
    cy.viewport(1024, 768);
    cy.visit(`${PLATFORM}${name}`, { headers: { Accept: 'text/html' } });

    cy.get('table.triples tbody tr')
      .filter(':contains("lorem ipsum")')
      .first()
      .within(() => {
        cy.get('td.term').should('have.length', 2).each((cell) => {
          expect(cell.css('white-space')).to.eq('nowrap');
          // one line: the term itself is no taller than a line of 13px text (the cell is as tall as
          // its row, which the long object makes tall)
          expect(cell.find('.clip')[0].getBoundingClientRect().height).to.be.lessThan(24);
        });
        cy.get('td.term .clip').eq(1).should('have.attr', 'title', 'http://purl.org/dc/terms/description');
        cy.get('td.value').then((cell) => {
          // the object wraps over many lines and takes most of the width
          expect(cell[0].getBoundingClientRect().height).to.be.greaterThan(100);
          expect(cell[0].getBoundingClientRect().width).to.be.greaterThan(500);
        });
      });
  });
});
