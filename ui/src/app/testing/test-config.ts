import { Provider, signal } from '@angular/core';

import { AuthConfig, ConfigService, UiConfig } from '../core/config.service';

/** Platform base used by the tests. */
export const TEST_BASE = 'http://host/platform/';

/** Topology service base used by the tests. */
export const TEST_TOPOLOGY = 'http://host/topology/';

/** Transform service base used by the tests. */
export const TEST_TRANSFORM = 'http://host/transform/';

/** Sign-in settings used by the tests unless they pass others: a pasted token. */
export const TEST_AUTH: AuthConfig = {
  provider: 'token',
  oidc: { authority: 'http://host/auth/realms/test', clientId: 'test-ui', scope: 'openid' },
};

/**
 * Provides a {@link ConfigService} with fixed settings, so tests need no `api/config` request.
 *
 * @param auth - the sign-in settings
 * @returns the provider
 */
export function provideTestConfig(auth: AuthConfig = TEST_AUTH): Provider {
  const config = signal<UiConfig>({ platformUrl: TEST_BASE, title: 'Test repository' });
  return {
    provide: ConfigService,
    useValue: {
      config,
      platformBase: () => TEST_BASE,
      apiDocs: () => 'http://host/swagger-ui.html',
      topologyBase: () => TEST_TOPOLOGY,
      transformBase: () => TEST_TRANSFORM,
      communitiesPath: () => 'communities',
      profilesPath: () => 'profiles',
      auth: () => auth,
      load: () => Promise.resolve(),
    } satisfies Partial<ConfigService>,
  };
}

/** N-Triples of a basic container `books` with a title and two children. */
export const CONTAINER_NT = `
<${TEST_BASE}books> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/ns/ldp#BasicContainer> .
<${TEST_BASE}books> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/ns/ldp#Container> .
<${TEST_BASE}books> <http://purl.org/dc/terms/title> "Books" .
<${TEST_BASE}books> <http://fedora.info/definitions/v4/repository#created> "2026-10-30T12:00:00Z"^^<http://www.w3.org/2001/XMLSchema#dateTime> .
<${TEST_BASE}books> <http://fedora.info/definitions/v4/repository#createdBy> "alice" .
<${TEST_BASE}books> <http://www.w3.org/ns/ldp#contains> <${TEST_BASE}books/moby-dick> .
<${TEST_BASE}books> <http://www.w3.org/ns/ldp#contains> <${TEST_BASE}books/cover.png> .
<${TEST_BASE}books> <http://purl.org/dc/terms/creator> _:b0 .
_:b0 <http://xmlns.com/foaf/0.1/name> "Librarian" .
`;

/** N-Triples of the description of a binary `books/cover.png`. */
export const BINARY_NT = `
<${TEST_BASE}books/cover.png> <http://www.w3.org/1999/02/22-rdf-syntax-ns#type> <http://www.w3.org/ns/ldp#NonRDFSource> .
<${TEST_BASE}books/cover.png> <http://www.loc.gov/premis/rdf/v1#hasSize> "2048"^^<http://www.w3.org/2001/XMLSchema#long> .
<${TEST_BASE}books/cover.png> <http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#hasMimeType> "image/png" .
<${TEST_BASE}books/cover.png> <http://www.ebu.ch/metadata/ontologies/ebucore/ebucore#filename> "cover.png" .
`;
