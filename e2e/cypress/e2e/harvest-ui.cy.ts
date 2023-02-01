/**
 * The Harvest page of the user interface, against the harvest service's API on the gateway
 * (/harvest/sources). The compose stack's demo sources (self-oai, self-ldp) harvest this repository
 * itself; the default root ACL lets everyone write, so the anonymous user may run harvests.
 *
 * States the stack cannot produce on demand (refused access, an unavailable service, failed
 * harvests) are answered by cy.intercept with what the harvest service sends in those cases.
 */

/** The harvest API, through the gateway. */
const API = '/harvest/sources';

/** A finished harvest, as the harvest service reports it. */
function report(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    sourceId: 'stubbed',
    type: 'oai-pmh',
    mode: 'INCREMENTAL',
    outcome: 'COMPLETED',
    startedAt: '2026-11-06T10:00:00Z',
    finishedAt: '2026-11-06T10:01:00Z',
    from: '2026-11-05T10:00:00Z',
    checkpoint: '2026-11-06T09:55:00Z',
    pages: 1,
    harvested: 3,
    created: 3,
    updated: 0,
    deleted: 0,
    skipped: 0,
    failed: 0,
    failures: [],
    message: null,
    ...overrides,
  };
}

/** A source, as the harvest service reports it. */
function source(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    id: 'stubbed',
    type: 'oai-pmh',
    url: 'https://repo.example/oai',
    enabled: true,
    schedule: null,
    capabilities: { incremental: true, deletions: true },
    running: null,
    reports: [],
    ...overrides,
  };
}

/** A problem document of the harvest service. */
function problem(status: number, title: string, detail: string) {
  return { statusCode: status, headers: { 'Content-Type': 'application/problem+json', 'Content-Language': 'en' }, body: { status, title, detail } };
}

const card = (id: string) => cy.get(`.ui-harvest-source[data-source="${id}"]`);

/** The part of a report the tests compare with the page. */
interface Report {
  readonly outcome: string;
  readonly startedAt: string;
  readonly harvested: number;
}

/**
 * Waits through the API until a source has the report of a harvest started after an instant.
 *
 * @param id - the source
 * @param after - epoch milliseconds the harvest was requested at
 * @param attempt - the current attempt
 * @returns the report
 */
function reportAfter(id: string, after: number, attempt = 1): Cypress.Chainable<Report> {
  return cy.request<{ running: unknown; reports: Report[] }>(`${API}/${id}`).then((response) => {
    const latest = response.body.reports[0];
    // a second of slack: the service's clock and this one may differ slightly
    if (!response.body.running && latest && Date.parse(latest.startedAt) >= after - 1000) {
      return cy.wrap(latest);
    }
    if (attempt >= 60) {
      throw new Error(`no report of a harvest of ${id} after ${new Date(after).toISOString()}`);
    }
    return cy.wait(1000).then(() => reportAfter(id, after, attempt + 1));
  });
}

/** The page's text of each outcome. */
const OUTCOMES: Record<string, string> = {
  COMPLETED: 'Completed',
  COMPLETED_WITH_ERRORS: 'Completed with errors',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
};

describe('Harvest page', () => {
  it('serves the sources through the gateway without credentials', () => {
    cy.request<{ sources: { id: string; url: string }[]; operate: boolean }>(API).then((response) => {
      expect(response.status).to.eq(200);
      expect(response.body.operate).to.eq(true);
      expect(response.body.sources.map((s) => s.id)).to.include.members(['self-oai', 'self-ldp']);
      for (const s of response.body.sources) {
        expect(s.url).not.to.match(/\/\/[^/]*@/);
      }
    });
    cy.request({ method: 'POST', url: `${API}/self-oai/harvests`, body: { mode: 'sideways' }, failOnStatusCode: false }).then(
      (response) => {
        expect(response.status).to.eq(400);
        expect(response.headers['content-type']).to.contain('application/problem+json');
      },
    );
    // the actuator endpoint stays on the management port
    cy.request({ url: '/harvest/actuator/harvest', failOnStatusCode: false }).its('status').should('not.eq', 200);
  });

  it('lists the configured sources and what they support', () => {
    cy.visitUi('harvest');
    cy.get('h1').should('have.text', 'Harvest');
    card('self-oai').should('contain.text', 'OAI-PMH').and('contain.text', 'Incremental harvests').and('contain.text', 'Reports deletions');
    card('self-ldp').should('contain.text', 'Fedora').and('contain.text', 'On demand only');
    cy.get('.ui-harvest-read-only').should('not.exist');
  });

  it('runs an incremental harvest from the page and shows its report', () => {
    cy.visitUi('harvest');
    const requested = Date.now();
    card('self-oai').find('.ui-harvest-incremental').should('be.enabled').click();
    cy.notified('Harvest of self-oai started');
    reportAfter('self-oai', requested).then((done) => {
      expect(done.outcome).to.eq('COMPLETED');
      // the page follows the harvest until it ends, then shows the same report
      card('self-oai').find('.ui-harvest-running', { timeout: 20000 }).should('not.exist');
      card('self-oai')
        .find('.ui-harvest-report .ui-outcome')
        .should('contain.text', OUTCOMES[done.outcome])
        .and('have.class', 'ui-outcome-success');
      card('self-oai').find('.ui-harvest-report .ui-count-harvested').should('have.text', String(done.harvested));
    });
    card('self-oai').find('.ui-harvest-report').should('contain.text', 'Incremental');
    card('self-oai').find('.ui-harvest-incremental').should('be.enabled');
  });

  it('asks before a full harvest and starts none when the user declines', () => {
    cy.intercept('POST', `**${API}/self-ldp/harvests`).as('start');
    cy.visitUi('harvest');
    card('self-ldp').find('.ui-harvest-full').click();
    cy.get('ui-confirm-dialog').should('contain.text', 'Start a full harvest?').contains('button', 'Cancel').click();
    cy.get('ui-confirm-dialog').should('not.exist');
    cy.wait(500);
    cy.get('@start.all').should('have.length', 0);
  });

  it('runs a full harvest once confirmed and cancels it', () => {
    cy.visitUi('harvest');
    const requested = Date.now();
    card('self-ldp').find('.ui-harvest-full').click();
    cy.get('ui-confirm-dialog').contains('button', 'Full harvest').click();
    cy.notified('Harvest of self-ldp started');
    // the harvest may end before the cancel button is pressed: either way the page must say how it ended
    card('self-ldp').then(($card) => {
      const cancel = $card.find('.ui-harvest-cancel:enabled');
      if (cancel.length > 0) {
        cy.wrap(cancel).click();
        cy.get('ui-confirm-dialog').contains('button', 'Cancel harvest').click();
      }
    });
    reportAfter('self-ldp', requested).then((done) => {
      expect(done.outcome).to.be.oneOf(['COMPLETED', 'CANCELLED']);
      card('self-ldp').find('.ui-harvest-running', { timeout: 20000 }).should('not.exist');
      card('self-ldp').find('.ui-harvest-report .ui-outcome').should('contain.text', OUTCOMES[done.outcome]);
      if (done.outcome === 'CANCELLED') {
        card('self-ldp').find('.ui-harvest-checkpoint').should('contain.text', 'Checkpoint not moved');
      }
    });
  });

  it('shows failed harvests and their failed records without calling them successful', () => {
    const withErrors = report({
      outcome: 'COMPLETED_WITH_ERRORS',
      checkpoint: null,
      failed: 2,
      failures: [{ identifier: 'oai:repo.example:7', reason: 'Unmappable metadata' }],
    });
    const failed = report({ outcome: 'FAILED', checkpoint: null, message: 'The platform answered 403 to store /harvested', startedAt: '2026-11-05T10:00:00Z' });
    cy.intercept('GET', `**${API}`, { body: { operate: true, sources: [source({ reports: [withErrors, failed] })] } });
    cy.visitUi('harvest');

    card('stubbed').find('.ui-harvest-report .ui-outcome').should('contain.text', 'Completed with errors');
    card('stubbed').find('.ui-harvest-report .ui-outcome-success').should('not.exist');
    card('stubbed').find('.ui-harvest-checkpoint').should('contain.text', 'Checkpoint not moved');
    card('stubbed').find('.ui-harvest-failures-panel').click();
    card('stubbed').find('.ui-harvest-failures').should('contain.text', 'oai:repo.example:7: Unmappable metadata');
    card('stubbed').find('.ui-harvest-failures-panel').should('contain.text', 'Showing the first 1 of 2 failed records.');
    card('stubbed').find('.ui-harvest-earlier').click();
    card('stubbed').find('.ui-harvest-history .ui-outcome-error').should('contain.text', 'Failed');
  });

  it('lets users without write access follow harvests only', () => {
    cy.intercept('GET', `**${API}`, { body: { operate: false, sources: [source({ reports: [report()] })] } });
    cy.visitUi('harvest');
    cy.get('.ui-harvest-read-only').should('contain.text', 'write access');
    card('stubbed').find('.ui-harvest-incremental').should('be.disabled');
    card('stubbed').find('.ui-harvest-full').should('be.disabled');
  });

  it('reports a harvest the service refuses', { expose: { allowConsoleErrors: true } }, () => {
    cy.intercept('GET', `**${API}`, { body: { operate: true, sources: [source()] } });
    cy.intercept('POST', `**${API}/stubbed/harvests`, problem(409, 'Conflict', 'Harvest source stubbed is being harvested'));
    cy.visitUi('harvest');
    card('stubbed').find('.ui-harvest-incremental').click();
    cy.notified('Harvest source stubbed is being harvested');
  });

  it('asks anonymous users to sign in', { expose: { allowConsoleErrors: true } }, () => {
    cy.intercept('GET', `**${API}`, problem(401, 'Unauthorized', 'Harvests need read access to /harvested'));
    cy.visitUi('harvest');
    cy.get('.ui-harvest-error').should('contain.text', 'Sign in to see the harvests.');
    cy.get('.ui-harvest-source').should('not.exist');
  });

  it('tells signed-in users without read access that they cannot see harvests', { expose: { allowConsoleErrors: true } }, () => {
    cy.intercept('GET', `**${API}`, problem(403, 'Forbidden', 'Harvests need read access to /harvested'));
    cy.visitUi('harvest');
    cy.get('.ui-harvest-error').should('contain.text', 'You cannot see the harvests');
  });

  it('reports an unreachable harvest service and recovers on retry', { expose: { allowConsoleErrors: true } }, () => {
    let calls = 0;
    cy.intercept('GET', `**${API}`, (request) => {
      calls += 1;
      if (calls === 1) {
        request.reply({ statusCode: 503, body: '' });
      } else {
        request.reply({ body: { operate: true, sources: [source()] } });
      }
    });
    cy.visitUi('harvest');
    cy.get('.ui-harvest-error').should('contain.text', 'The harvest service cannot be reached');
    cy.get('.ui-harvest-error').contains('button', 'Retry').click();
    cy.get('.ui-harvest-error').should('not.exist');
    card('stubbed').should('exist');
  });

  it('is reachable from the navigation', () => {
    cy.visitUi('browse');
    cy.get('.ui-nav').contains('a', 'Harvest').click();
    cy.location('pathname').should('eq', '/ui/harvest');
  });
});
