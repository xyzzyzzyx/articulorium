import { defineConfig } from 'cypress';

/**
 * End-to-end tests of the user interface against the running docker compose stack, through the
 * gateway (see README.md). Override the address with CYPRESS_BASE_URL.
 */
export default defineConfig({
  e2e: {
    baseUrl: 'http://localhost:8000',
    specPattern: 'cypress/e2e/**/*.cy.ts',
    supportFile: 'cypress/support/e2e.ts',
    viewportWidth: 1400,
    viewportHeight: 1000,
    defaultCommandTimeout: 10000,
    video: false,
    screenshotOnRunFailure: true,
    retries: { runMode: 1, openMode: 0 },
  },
});
