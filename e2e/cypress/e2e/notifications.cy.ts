import { PLATFORM } from '../support/e2e';

describe('Notifications (COAR Notify)', () => {
  it('lists received notifications and records sent ones', { expose: { allowConsoleErrors: true } }, () => {
    cy.createContainer('Inbox').then((name) => {
      const target = `${Cypress.config('baseUrl')}${PLATFORM}${name}`;
      cy.request({
        method: 'POST',
        url: `${PLATFORM}${name}/fcr:inbox`,
        headers: { 'Content-Type': 'application/ld+json' },
        body: {
          '@context': ['https://www.w3.org/ns/activitystreams', 'https://coar-repositories.org/notify'],
          id: `urn:uuid:${crypto.randomUUID()}`,
          type: ['Announce', 'coar-notify:ReviewAction'],
          origin: { id: 'https://review.example.org/', inbox: 'https://review.example.org/inbox', type: 'Service' },
          target: { id: target, inbox: `${target}/fcr:inbox`, type: 'Service' },
          object: { id: 'https://review.example.org/reviews/42', type: ['Page', 'sorg:Review'] },
          actor: { id: 'https://review.example.org/', type: 'Service', name: 'Review service' },
        },
      })
        .its('status')
        .should('eq', 201);

      cy.visitUi(`browse/${name}`);
      cy.get('aside').contains('a', 'Notifications').click();
      cy.location('pathname').should('eq', `/ui/inbox/${name}`);
      cy.get('.ui-inbox-url').should('contain.text', `${name}/fcr:inbox`);
      cy.get('.ui-inbox mat-expansion-panel').should('have.length', 1).click();
      cy.get('.ui-inbox').should('contain.text', 'Announce Review').and('contain.text', 'https://review.example.org/reviews/42');
      cy.get('.ui-inbox .ui-json').should('contain.text', 'coar-notify:ReviewAction');

      // nothing listens on the target: the delivery is recorded as failed
      cy.get('.ui-send-notification input[name=targetId]').type('http://127.0.0.1:9/');
      cy.get('.ui-send-notification input[name=targetInbox]').type('http://127.0.0.1:9/inbox');
      cy.get('.ui-send-notification').contains('button', 'Send').click();
      cy.notified(/Delivery failed/);
      cy.get('.ui-outbox').should('contain.text', 'Offer Review').and('contain.text', 'FAILED');
    });
  });

  it('rejects notifications that are not COAR Notify', () => {
    cy.createContainer('Strict inbox').then((name) => {
      cy.request({
        method: 'POST',
        url: `${PLATFORM}${name}/fcr:inbox`,
        headers: { 'Content-Type': 'application/ld+json' },
        body: { type: 'Note' },
        failOnStatusCode: false,
      })
        .its('status')
        .should('eq', 400);
    });
  });
});
