# Articulorum end-to-end tests

[Cypress](https://www.cypress.io/) tests that drive the user interface in a real browser against
the running docker compose stack, through the gateway, exactly as a user would.

## Running

```bash
docker compose up --build -d --wait          # the stack (from the repository root)
docker compose --profile e2e run --rm e2e    # the tests
```

The `e2e` compose service (image `cypress/included`, only started with the `e2e` profile) mounts
this folder, installs its packages into its own `node_modules` volume (never the host's, which
may be built for another OS), forwards its `localhost:8000` to the gateway so every URL is the
same as in a browser on the host, waits for the user interface and the platform, and runs
`cypress run`.

| Variable      | Default  | Effect                                                              |
|---------------|----------|---------------------------------------------------------------------|
| `E2E_BROWSER` | `chrome` | browser for `cypress run`: `chrome`, `firefox` or `electron`         |
| `E2E_VIDEO`   | `false`  | `true` records what the browser shows to `cypress/videos`           |
| `E2E_ARGS`    |          | extra `cypress run` arguments, e.g. `--spec cypress/e2e/search.cy.ts` |

```bash
E2E_ARGS="--spec cypress/e2e/versions.cy.ts" docker compose --profile e2e run --rm e2e
```

Screenshots of failing tests land in `cypress/screenshots`. GitHub Actions runs the suite in the
`compose` job after starting the stack and uploads those screenshots when it fails.

Running Cypress on the host instead (with the stack up): `npm ci`, then `npm test`, or `npm run
open` for the interactive runner. `CYPRESS_BASE_URL` points the tests elsewhere.

## Specs

| Spec                | Covers                                                                   |
|---------------------|--------------------------------------------------------------------------|
| `navigation.cy.ts`  | gateway root and `/ui` redirects, main navigation (API link to the gateway's Swagger UI), children and breadcrumbs, deep links, missing resources |
| `containers.cy.ts`  | basic containers at chosen or minted identifiers, no overwriting, invalid Turtle, direct/indirect templates, blank nodes |
| `binaries.cy.ts`    | upload with metadata, download, replace content, fixity, editing a binary's description |
| `properties.cy.ts`  | SPARQL Update with the prefix preamble, reset, invalid updates and protected triples |
| `versions.cy.ts`    | versions of binaries (with historic bytes and technical metadata) and containers, the root, empty lists |
| `search.cy.ts`      | conditions, shareable URLs, ordering and paging                           |
| `delete.cy.ts`      | cancel, delete binaries and containers recursively                       |
| `archival-groups.cy.ts` | creating archival groups from the form, members versioned through their group |
| `permissions.cy.ts` | effective access, granting and revoking on the Permissions page, enforcement of ACLs |
| `communities.cy.ts` | communities, sub-communities and collections; building a book from a topology, adding pages and metadata |
| `topologies.cy.ts`  | built-in topologies, duplicating, editing and deleting a custom one, building from the topology aside |
| `notifications.cy.ts` | COAR Notify inbox listing and details, sending with recorded delivery failures, invalid notifications |
| `iiif.cy.ts`        | IIIF Manifest of a book (image uploaded through the topology aside) and Collection of the communities |

Each test creates its own uniquely named resources through the LDP API, so the suite runs
against a repository that already holds data, and fails when the application logs a console
error (tests that expect errors opt out with `{ expose: { allowConsoleErrors: true } }`).
Shared commands live in `cypress/support/e2e.ts`; `npm run typecheck` type-checks the specs.

The `cypress` package version must match the `cypress/included` image tag in
`docker-compose.yml`, so the container uses the browser runner the image ships.
