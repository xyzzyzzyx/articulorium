import { PLATFORM, pathOf, unique } from '../support/e2e';

/**
 * The AI agent (the ai service, /ai/ on the gateway) in the user interface.
 *
 * The compose stack the tests run against deploys the agent with its model, so the first part
 * checks that it is offered: its capabilities, its actions, and harvested records classified and
 * accepted automatically. The other parts check the flows of reviewing and applying suggestions;
 * the agent's answers are given by cy.intercept (a model does not answer the same twice), while
 * everything a person keeps is written to the real platform and topology service and read back
 * from them.
 */

const DCTERMS = 'http://purl.org/dc/terms/';
const ART = 'https://articulorium.org/ns/repository#';
const PCDM_COLLECTION = 'http://pcdm.org/models#Collection';

/** What the agent offers when it runs with a model. */
const OFFERS_ALL = {
  enabled: true,
  model: true,
  modelName: 'ollama:qwen2.5vl:3b',
  capabilities: { topology: true, media: true, topics: true, profiles: true, classification: true },
};

/** A problem document of the agent. */
function agentProblem(status: number, reason: string, detail: string) {
  return {
    statusCode: status,
    headers: { 'Content-Type': 'application/problem+json' },
    body: { type: 'about:blank', title: 'AI agent', status, detail, reason },
  };
}

/** Creates a community below the community structure through the LDP API; yields its path. */
function createCommunity(title: string): Cypress.Chainable<string> {
  return cy
    .request({
      method: 'POST',
      url: `${PLATFORM}communities`,
      headers: { 'Content-Type': 'text/turtle', Slug: unique('community') },
      body: `<> a <${ART}Community> ; <${DCTERMS}title> "${title}" .`,
    })
    .then((response) => pathOf(response.headers.location as string));
}

/**
 * The Communities page of a community or collection: its route is the path below the community
 * structure (`communities/lib` is `/ui/communities/lib`).
 */
function visitStructure(path: string): void {
  cy.visitUi(`communities/${path.replace(/^communities\/?/, '')}`);
}

/** Reads a resource's N-Triples through the LDP API. */
function ntriples(path: string): Cypress.Chainable<string> {
  return cy
    .request({ url: `${PLATFORM}${path}`, headers: { Accept: 'application/n-triples' } })
    .then((response) => response.body as string);
}

describe('AI agent', () => {
  context('as deployed by default', () => {
    it('offers its capabilities with a model, and the user interface its actions', () => {
      cy.request<typeof OFFERS_ALL>('/ai/capabilities').then((response) => {
        expect(response.body.enabled).to.eq(true);
        expect(response.body.model, 'the pulled model').to.eq(true);
        expect(response.body.capabilities.classification).to.eq(true);
      });

      cy.visitUi('topologies');
      cy.get('h1').should('have.text', 'Topologies');
      cy.get('.ui-ai-topology').should('exist');
    });

    it('classifies harvested records: the Harvest page has the review queue', () => {
      cy.request<{ classification: boolean }>('/harvest/sources').its('body.classification').should('eq', true);
      cy.visitUi('harvest');
      cy.get('.ui-harvest-source').should('exist');
      cy.get('ui-review-queue').should('exist');
    });
  });

  context('suggestions reviewed and applied', () => {
    beforeEach(() => {
      cy.intercept('GET', '**/ai/capabilities', OFFERS_ALL).as('capabilities');
    });

    it('adds only the metadata of a binary the user keeps to its description', () => {
      cy.createContainer(unique('Covers')).then((parent) =>
        cy.createBinary(parent, { name: 'whale.png', type: 'image/png', content: 'not really a PNG' }).then((binary) => {
          cy.intercept('POST', '**/ai/media-metadata', (request) => {
            expect(request.body).to.deep.equal({ path: binary });
            request.reply({
              path: binary,
              description: `${binary}/fcr:metadata`,
              mediaType: 'image/png',
              kind: 'IMAGE',
              imageShown: false,
              statements: [
                { predicate: `${DCTERMS}title`, value: 'A whale breaching', kind: 'LITERAL', language: 'en', origin: 'MODEL' },
                { predicate: `${DCTERMS}subject`, value: 'Unwanted subject', kind: 'LITERAL', language: 'en', origin: 'MODEL' },
              ],
              notes: ['IMAGE_FORMAT_NOT_SHOWN'],
            });
          }).as('describe');

          cy.visitUi(`browse/${binary}`);
          cy.get('.ui-ai-card').should('contain.text', 'AI metadata suggestions');
          cy.get('.ui-media-suggestion').contains('button', 'Suggest metadata').click();
          cy.wait('@describe');
          cy.get('.ui-ai-notes').should('contain.text', 'its format is not supported');
          cy.get('.ui-ai-statements li').should('have.length', 2);
          cy.get('.ui-ai-statements li').eq(1).find('input[type=checkbox]').uncheck({ force: true });
          cy.get('.ui-ai-apply').should('contain.text', 'Add 1 statement').click();
          cy.notified('Added 1 statement');

          // the platform has the statement kept, and only that one
          cy.request({ url: `${PLATFORM}${binary}`, headers: { Accept: 'application/n-triples' } }).then((response) => {
            expect(response.body).to.contain('"A whale breaching"@en');
            expect(response.body).not.to.contain('Unwanted subject');
          });
        }),
      );
    });

    it('explains why the agent could not help, and changes nothing', () => {
      cy.createContainer(unique('Reports')).then((parent) =>
        cy.createBinary(parent, { name: 'report.pdf', type: 'application/pdf', content: '%PDF-1.4' }).then((binary) => {
          cy.intercept('POST', '**/ai/media-metadata', agentProblem(503, 'MODEL_UNAVAILABLE', 'No chat model is configured'));
          cy.visitUi(`browse/${binary}`);
          cy.get('.ui-media-suggestion').contains('button', 'Suggest metadata').click();
          cy.get('.ui-media-suggestion [role=alert]').should('have.text', 'No AI model is available.');
          cy.get('.ui-ai-apply').should('not.exist');
        }),
      );
    });

    it('adds the topics and summary kept to a community', () => {
      createCommunity(unique('Maritime')).then((community) => {
        cy.intercept('POST', '**/ai/topic-models', {
          path: community,
          summary: 'Logbooks and letters of whaling voyages.',
          topics: [
            { label: 'Whaling', keywords: ['whale'], description: 'Hunting whales.', members: [], share: 0.7 },
            { label: 'Navigation', keywords: ['charts'], description: 'Finding the way.', members: [], share: 0.3 },
          ],
          analysed: 2,
          truncated: false,
          language: 'en',
        }).as('topics');

        visitStructure(community);
        cy.get('.ui-topic-suggestion').contains('button', 'Suggest topics').click();
        cy.wait('@topics');
        cy.get('.ui-topic-suggestion').should('contain.text', 'Based on 2 items.');
        cy.get('.ui-ai-topics > li').eq(1).find('input[type=checkbox]').uncheck({ force: true });
        cy.get('.ui-topic-suggestion .ui-ai-apply').click();
        cy.notified('Added 1 topic');

        ntriples(community).then((body) => {
          expect(body).to.contain(`<${DCTERMS}subject> "Whaling"@en`);
          expect(body).not.to.contain('"Navigation"');
          expect(body).to.contain(`<${DCTERMS}abstract> "Logbooks and letters of whaling voyages."@en`);
        });
      });
    });

    it('merges the research areas, keywords and overview kept into a profile', () => {
      const family = unique('Meitner');
      cy.visitUi('profiles/new');
      cy.get('input[name=givenName]').type('Lise');
      cy.get('input[name=familyName]').type(family);
      cy.get('.ui-profile-keywords-field input').type('fission{enter}');
      cy.get('.ui-add-position').click();
      cy.get('input[name=position-title-0]').type('Professor');
      cy.get('input[name=position-organization-0]').type('Kaiser Wilhelm Institute');
      cy.get('input[name=position-start-0]').type('1926');
      cy.get('.ui-save-profile').click();
      cy.notified('Profile created');
      cy.location('pathname').should('match', /^\/ui\/profiles\/.+/);

      cy.location('pathname').then((pathname) => {
        const path = `profiles/${pathname.substring('/ui/profiles/'.length)}`;
        cy.intercept('POST', '**/ai/profile-suggestions', (request) => {
          expect(request.body).to.deep.equal({ path });
          request.reply({
            path,
            overview: 'Lise Meitner is a physicist who studies radioactivity and nuclear fission.',
            hasOverview: false,
            researchAreas: ['Nuclear physics', 'Radiochemistry'],
            keywords: ['beta decay', 'unwanted keyword'],
            basedOn: { positions: 1, education: 0, publications: 0 },
            language: 'en',
          });
        }).as('suggestProfile');

        cy.get('ui-profile-suggestion').contains('button', 'Suggest profile details').click();
        cy.wait('@suggestProfile');
        cy.get('.ui-ai-keywords li').eq(1).find('input[type=checkbox]').uncheck({ force: true });
        cy.get('ui-profile-suggestion .ui-ai-apply').click();
        cy.notified('Profile updated');

        // saved as the profile editor saves it: research areas as concepts, keywords merged
        cy.get('.ui-profile-overview').should('contain.text', 'studies radioactivity and nuclear fission');
        cy.get('.ui-profile-areas').should('contain.text', 'Nuclear physics').and('contain.text', 'Radiochemistry');
        cy.get('.ui-profile-keywords').should('contain.text', 'beta decay').and('contain.text', 'fission')
          .and('not.contain.text', 'unwanted keyword');
        ntriples(path).then((body) => {
          expect(body).to.contain('http://www.w3.org/2004/02/skos/core#Concept');
          expect(body).to.contain('"Nuclear physics"');
          expect(body).to.contain('"Kaiser Wilhelm Institute"');
        });
      });
    });

    it('opens a drafted topology in the editor, where it is reviewed and saved', () => {
      const id = unique('letters');
      cy.intercept('POST', '**/ai/topology-suggestions', (request) => {
        expect(request.body.description).to.equal('Letters, each with page scans and a transcription');
        request.reply({
          recommendation: 'NEW',
          draft: {
            id,
            name: 'Letters',
            description: 'A letter with its pages',
            root: {
              key: 'letter',
              label: 'Letter',
              pcdmType: 'Object',
              metadata: [{ predicate: `${DCTERMS}title`, label: 'Title', required: true }],
              files: [{ use: 'http://pcdm.org/use#Transcript', label: 'Transcription', mediaTypes: ['text/plain'] }],
              children: [{ min: 1, max: -1, initial: 1, node: { key: 'page', label: 'Page', files: [{ use: 'http://pcdm.org/use#OriginalFile', label: 'Scan' }] } }],
            },
          },
          errors: [],
          rationale: 'No existing topology has transcriptions.',
          confidence: 0.7,
        });
      }).as('suggest');

      cy.visitUi('topologies');
      cy.get('.ui-ai-topology').click();
      cy.get('ui-topology-suggestion-dialog textarea[name=description]').type('Letters, each with page scans and a transcription');
      cy.get('ui-topology-suggestion-dialog').contains('button', 'Suggest').click();
      cy.wait('@suggest');
      cy.get('.ui-ai-result').should('contain.text', 'A new topology: “Letters”').and('contain.text', 'Confidence 70 %');
      cy.get('.ui-ai-open').should('have.text', 'Review in the editor').click();

      cy.location('pathname').should('eq', '/ui/topologies/new');
      cy.location('search').should('contain', 'origin=ai');
      cy.get('input[name=id]').should('have.value', id);
      cy.get('input[name=name]').should('have.value', 'Letters');
      // nothing was saved before the person did
      cy.request({ url: `/topology/topologies/${id}`, failOnStatusCode: false }).its('status').should('eq', 404);
      cy.contains('button', 'Save').click();
      cy.notified('Topology saved');
      cy.request(`/topology/topologies/${id}`).its('body.root.children.0.node.key').should('eq', 'page');
    });
  });

  context('review queue of harvested records', () => {
    it('creates a proposed collection once confirmed and places the record there, or rejects the suggestion', () => {
      const community = unique('Harvested');
      createCommunity(community).then((communityPath) => {
        const proposed = {
          source: 'self-oai',
          record: 'harvest/self-oai/rec-proposed',
          identifier: 'oai:localhost:rec-proposed',
          title: 'Ship logs of the Essex',
          status: 'PENDING',
          suggestion: {
            placement: 'NEW_COLLECTION',
            community: { path: communityPath, title: community, trail: community },
            newCollection: { title: 'Ship logs', slug: 'ship-logs', description: 'Logbooks of ships.' },
            topology: { kind: 'NONE' },
            confidence: 0.42,
            rationale: 'No collection holds logbooks yet.',
          },
          uncertain: true,
          classifiedAt: '2026-11-07T10:00:00Z',
        };
        const pending = {
          source: 'self-oai',
          record: 'harvest/self-oai/rec-pending',
          identifier: 'oai:localhost:rec-pending',
          title: 'Moby Dick',
          status: 'PENDING',
          suggestion: {
            placement: 'EXISTING_COLLECTION',
            collection: { path: `${communityPath}/novels`, title: 'Novels', trail: `${community} › Novels` },
            topology: { kind: 'EXISTING', id: 'book', name: 'Book' },
            confidence: 0.9,
          },
          uncertain: false,
          classifiedAt: '2026-11-07T10:01:00Z',
        };

        // the harvest service as it is with classification turned on
        cy.intercept('GET', '**/harvest/sources', (request) =>
          request.continue((response) => {
            response.body.classification = true;
          }),
        );
        cy.intercept('GET', '**/harvest/classifications?*', {
          classifications: [proposed, pending],
          total: 2,
          offset: 0,
          decide: true,
        }).as('queue');
        cy.intercept('POST', '**/harvest/classifications/self-oai/rec-proposed/acceptance', (request) =>
          request.reply({ ...proposed, status: 'ACCEPTED', placedAt: `${request.body.collection}/rec-proposed` }),
        ).as('accept');
        cy.intercept('POST', '**/harvest/classifications/self-oai/rec-pending/rejection', { ...pending, status: 'REJECTED' }).as('reject');

        cy.visitUi('harvest');
        cy.wait('@queue');
        cy.get('.ui-review-item').should('have.length', 2);
        cy.get('.ui-review-item')
          .first()
          .should('have.class', 'ui-review-uncertain')
          .and('contain.text', `A new collection “Ship logs” in ${community}`)
          .and('contain.text', 'Confidence 42 %');

        cy.get('.ui-review-item').first().find('.ui-review-create').click();
        cy.get('ui-confirm-dialog').should('contain.text', 'Ship logs').contains('button', 'Create and accept').click();
        cy.wait('@accept').its('request.body').should('deep.equal', { collection: `${communityPath}/ship-logs`, topology: 'none' });
        cy.notified('“Ship logs of the Essex” was placed');

        // the collection was created on the platform, with the structure the Communities page creates
        ntriples(`${communityPath}/ship-logs`).then((body) => {
          expect(body).to.contain(`<${ART}Collection>`);
          expect(body).to.contain(`<${PCDM_COLLECTION}>`);
          expect(body).to.contain('"Ship logs"');
        });

        cy.get('.ui-review-item').eq(1).find('.ui-review-reject').click();
        cy.get('ui-confirm-dialog').should('contain.text', 'Moby Dick').contains('button', 'Reject').click();
        cy.wait('@reject');
        cy.notified('Rejected the suggestion for “Moby Dick”');
      });
    });
  });
});
