/**
 * Shared commands and checks of the end-to-end tests.
 *
 * Tests create their own uniquely named containers through the LDP API, so they are
 * independent of each other and of what the repository already holds.
 */

/** A binary file to upload. */
export interface UploadFile {
  readonly name: string;
  readonly type: string;
  readonly content: string;
}

declare global {
  // eslint-disable-next-line @typescript-eslint/no-namespace
  namespace Cypress {
    interface Chainable {
      /** Opens a view of the user interface (relative to /ui/). */
      visitUi(path?: string): Chainable<AUTWindow>;
      /** Creates a basic container below the root through the LDP API; yields its path. */
      createContainer(title: string, slug?: string): Chainable<string>;
      /** Creates a binary in a container through the LDP API; yields its path. */
      createBinary(parent: string, file: UploadFile): Chainable<string>;
      /** Selects an option of an Angular Material select. */
      chooseOption(select: string, option: string): Chainable<JQuery<HTMLElement>>;
      /** Asserts that a snack bar message is shown. */
      notified(text: string | RegExp): Chainable<JQuery<HTMLElement>>;
    }
  }
}

/** Platform root as reached through the gateway. */
export const PLATFORM = '/platform/';

/**
 * A name unique to this test run.
 *
 * @param prefix - readable prefix
 * @returns for example `books-l9x2k1a`
 */
export function unique(prefix: string): string {
  return `${prefix}-${Date.now().toString(36)}${Math.floor(Math.random() * 1296).toString(36)}`;
}

/**
 * Converts the IRI of a resource (as the platform mints it, under the gateway) into its path.
 *
 * @param iri - for example `http://localhost:8000/platform/books/1`
 * @returns `books/1`
 * @throws when the IRI is not under the gateway's platform route
 */
export function pathOf(iri: string): string {
  const { pathname } = new URL(iri);
  if (!pathname.startsWith(PLATFORM)) {
    throw new Error(`${iri} is not under ${PLATFORM}: the platform mints IRIs under another address`);
  }
  return pathname.substring(PLATFORM.length);
}

Cypress.Commands.add('visitUi', (path = '') => cy.visit(`/ui/${path.replace(/^\//, '')}`));

Cypress.Commands.add('createContainer', (title: string, slug?: string) => {
  const name = slug ?? unique('c');
  return cy
    .request({
      method: 'PUT',
      url: `${PLATFORM}${encodeURIComponent(name)}`,
      headers: { 'Content-Type': 'text/turtle' },
      body: `<> <http://purl.org/dc/terms/title> "${title}" .`,
    })
    .then(() => name);
});

Cypress.Commands.add('createBinary', (parent: string, file: UploadFile) => {
  return cy
    .request({
      method: 'POST',
      url: `${PLATFORM}${parent}`,
      headers: { 'Content-Type': file.type, Slug: file.name },
      body: file.content,
    })
    .then((response) => pathOf(String(response.headers['location'])));
});

Cypress.Commands.add('chooseOption', (select: string, option: string) => {
  cy.get(select).click();
  cy.get('mat-option').contains(new RegExp(`^\\s*${Cypress._.escapeRegExp(option)}\\s*$`)).click();
  // the panel closes with an animation; the next select must not click its backdrop
  return cy.get('.mat-mdc-select-panel').should('not.exist').then(() => cy.get(select));
});

Cypress.Commands.add('notified', (text: string | RegExp) => {
  return cy.get('mat-snack-bar-container').should('be.visible').and('contain.text', typeof text === 'string' ? text : '').then(($el) => {
    if (text instanceof RegExp) {
      expect($el.text()).to.match(text);
    }
  });
});

/** `console.error` calls of the application during the current test. */
const consoleErrors: string[] = [];

// every page the test loads (by cy.visit or a full navigation) reports its console errors;
// tests that only call the API load no page and so record none
Cypress.on('window:before:load', (win) => {
  const original = win.console.error.bind(win.console);
  win.console.error = (...args: unknown[]) => {
    consoleErrors.push(args.map(String).join(' '));
    original(...args);
  };
});

beforeEach(() => {
  consoleErrors.length = 0;
});

// the application must not log errors in any test that does not expect them
afterEach(function () {
  if (this.currentTest?.state === 'passed' && !Cypress.expose('allowConsoleErrors')) {
    cy.wrap(consoleErrors, { log: false }).should('deep.equal', []);
  }
});
