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

  it('receives Activity Streams notifications as application/activity+json', () => {
    cy.createContainer('Activity inbox').then((name) => {
      const target = `${Cypress.config('baseUrl')}${PLATFORM}${name}`;
      // the Activity Streams context is implied by the media type
      cy.request({
        method: 'POST',
        url: `${PLATFORM}${name}/fcr:inbox`,
        headers: { 'Content-Type': 'application/activity+json' },
        body: JSON.stringify({
          '@context': 'https://coar-repositories.org/notify',
          id: `urn:uuid:${crypto.randomUUID()}`,
          type: ['Announce', 'coar-notify:EndorsementAction'],
          origin: { id: 'https://overlay.example.org/', inbox: 'https://overlay.example.org/inbox', type: 'Service' },
          target: { id: target, inbox: `${target}/fcr:inbox`, type: 'Service' },
          object: { id: 'https://overlay.example.org/endorsements/7', type: 'Page' },
          published: '2026-11-05T17:32:00Z',
        }),
      }).then((response) => {
        expect(response.status).to.eq(201);
        cy.request({ url: response.headers['location'] as string, headers: { Accept: 'application/activity+json' } }).then(
          (notification) => {
            expect(notification.headers['content-type']).to.contain('application/activity+json');
            expect(notification.body['@context']).to.deep.eq([
              'https://www.w3.org/ns/activitystreams',
              'https://coar-repositories.org/notify',
            ]);
          },
        );
      });

      cy.visitUi(`inbox/${name}`);
      cy.get('.ui-inbox mat-expansion-panel').should('have.length', 1);
      cy.get('.ui-inbox').should('contain.text', 'Announce Endorsement');
    });
  });

  it('rejects malformed Activity Streams and other media types', () => {
    cy.createContainer('Picky inbox').then((name) => {
      const inbox = `${PLATFORM}${name}/fcr:inbox`;
      cy.request({ method: 'POST', url: inbox, headers: { 'Content-Type': 'text/plain' }, body: '{}', failOnStatusCode: false }).then(
        (response) => {
          expect(response.status).to.eq(415);
          expect(response.headers['accept-post']).to.contain('application/activity+json');
        },
      );
      cy.request({
        method: 'POST',
        url: inbox,
        headers: { 'Content-Type': 'application/activity+json' },
        body: '{"type": "Note", "type": "Like"}',
        failOnStatusCode: false,
      })
        .its('status')
        .should('eq', 400);
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
