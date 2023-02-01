/** A 3 x 2 pixel PNG. */
const PNG = 'iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAEklEQVR4nGP4z8AARAwM/xkYAB0EA/2d8F0yAAAAAElFTkSuQmCC';

describe('IIIF export', () => {
  it('exports a book built from a topology as a IIIF manifest', () => {
    cy.createContainer('IIIF shelf').then((shelf) => {
      cy.request({
        method: 'POST',
        url: '/topology/topologies/book/instances',
        body: { parent: shelf, title: 'Atlas', counts: { page: 1 } },
      }).then((built) => {
        const book = built.body.path as string;
        const page = `${book}/members/page-1`;
        // upload the page image through the topology aside, which records its PCDM use
        cy.visitUi(`browse/${page}`);
        cy.get('input[type=file][aria-label="Upload Master image"]').selectFile({
          contents: Cypress.Buffer.from(PNG, 'base64'),
          fileName: 'master.png',
          mimeType: 'image/png',
        });
        cy.notified('Master image uploaded');
        cy.get('.ui-topology-file').first().should('contain.text', 'master.png');
        cy.request(`/transform/iiif/${book}`).then((response) => {
          expect(response.headers['access-control-allow-origin']).to.eq('*');
          expect(response.body.type).to.eq('Manifest');
          expect(response.body.label).to.deep.equal({ none: ['Atlas'] });
          expect(response.body.items).to.have.length(1);
          const annotation = response.body.items[0].items[0].items[0];
          expect(annotation.motivation).to.eq('painting');
          expect(annotation.body.id).to.match(new RegExp(`/platform/${Cypress._.escapeRegExp(page)}/files/`));
          expect(annotation.body.format).to.eq('image/png');
          expect(response.body.items[0].width).to.eq(3);
          expect(response.body.items[0].height).to.eq(2);
        });
        cy.visitUi(`browse/${book}`);
        cy.get('.ui-iiif-link').should('have.attr', 'href', `${Cypress.config('baseUrl')}/transform/iiif/${book}`);
      });
    });
  });

  it('exports the community structure as a IIIF collection', () => {
    cy.request('/transform/iiif/communities').its('body.type').should('eq', 'Collection');
  });
});
