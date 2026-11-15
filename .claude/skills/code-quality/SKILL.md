---
name: code-quality
description: Articulorium's quality bar for writing and reviewing changes to its Spring Boot services, Angular UIs (ui, discovery-ui), tests, translations and docs. Use before calling any code change done, when reviewing a branch or pull request, or when asked to check code quality.
---

# Articulorium code quality

The conventions below are taken from the code as it is, not from a generic style guide. When a
file you touch already does something consistently, match it; when this skill and a neighbouring
file disagree, follow the neighbouring file and say so in your report.

Related project skills: `articulorium` (which module owns what), `spring-boot-best-practices`
and `angular-best-practices` (how to design a change). This skill is the bar every change is
checked against.

## How to use this skill

1. **Scope.** `git diff --stat main...` (or the files you changed). Note which modules and which
   areas below apply.
2. **Check** each changed file against the matching section. Look at the code around the change
   too: a fix that leaves the same bug one method away is not finished.
3. **Verify** with the commands in [Verification](#verification). If something cannot be run (no
   network, no Docker), say exactly what was not run.
4. **Report** in the format in [Reporting](#reporting).

## Repository map

Maven reactor (`pom.xml`, parent `spring-boot-starter-parent` 4.1, Spring Cloud 2025.1, Java 25).

| Kind | Modules |
|---|---|
| Libraries | `domain` (LDP model, vocabularies, RDF helpers), `event` (Bus events, Spring Integration flows), `activitystreams`, `agent`, `agent-spring-boot-starter`, `i18n-spring-boot-starter`, `consul-native` (native-image hints) |
| Services | `platform` (LDP, OCFL, WebAC), `transform`, `index` (Solr/Elasticsearch), `triplestore` (QLever/Blazegraph/Jena), `topology`, `oai`, `harvest`, `ai`, `discovery`, `service` (UI host), `gateway` |
| Front ends | `ui` (Angular, prefix `ui`), `discovery-ui` (Angular SSR, prefix `disc`) |
| Other | `e2e` (Cypress), `docs` + `site-check` (marketing site), `keycloak`, compose files |

## Java and Spring Boot

**Platform versions.** Spring Boot 4.1, Jackson 3 (`tools.jackson.*`; never
`com.fasterxml.jackson.databind`), `jakarta.*`, JSpecify `org.jspecify.annotations.Nullable`
for anything that may be null.

**Injection and construction**
- Constructor injection only; no `@Autowired` fields or setters.
- Fields, parameters and locals are `final` wherever they can be.
- A class with a test seam has two constructors: the public `@Autowired` one delegates to one that
  also takes the seam, most often a `Clock` (`Clock.systemUTC()` in production). Never read
  `Instant.now()` / `System.currentTimeMillis()` in code whose behaviour depends on time.
- HTTP clients are built from an injected `RestClient.Builder` with `builder.clone().build()`,
  using the module's load-balanced qualifier where its configuration provides one. No
  `RestTemplate`, no `WebClient`.

**Configuration**
- Settings live in a `@ConfigurationProperties(prefix = "articulorium.<module>")` class with
  Lombok `@Getter`/`@Setter`, defaults on the field, a Javadoc comment on every field (it becomes
  the configuration metadata) and nested `public static class` groups.
- `@Value` only for single framework properties (`spring.application.name`, a classpath
  resource).
- A new property or environment variable is documented in the module README (and the root README
  table when operators set it). Every property named in Markdown must exist in code.
- No secrets or deployment-specific values in packaged `application.yml`; operators override with
  environment variables or Consul (see `SECURITY.md`).

**Types and Lombok**
- Records for value types. Lombok is limited to `@Getter`, `@Setter`, `@Slf4j` and `@Value`;
  do not introduce `@Data`, `@Builder` or `@*ArgsConstructor`.
- Switch over the project's own enums exhaustively with arrow cases and no `default`, so a new
  constant breaks the build instead of being silently mapped.

**Errors (RFC 9457)**
- Controllers' errors go through a `@RestControllerAdvice` scoped with `assignableTypes`,
  returning `ResponseEntity<ProblemDetail>` with `Content-Language`, a `reason` property from the
  exception's reason enum, and `WWW-Authenticate: Bearer` on 401.
- The `detail` is a sentence for the client: no stack traces, class names, SQL, internal URLs or
  upstream bodies.
- A dependency that cannot answer means "not allowed" or "unavailable" (503), never "allowed".
  There is no circuit-breaker library: use timeouts and bounded retries.

**Security** (read `SECURITY.md` before changing anything access-related)
- Every service enforces WebAC itself by asking the platform; the gateway does not authorize.
- WebAC `write` does not imply `read`. Check the mode the operation needs.
- Match paths by whole segments (`actuator`, `actuator/**`), never `startsWith` on a raw string.
- Cache only answers the authority actually gave; a failed check denies now and is not
  remembered.
- Never log or echo tokens, `Authorization` headers, Consul ACL tokens or passwords.

**Correctness traps found in this codebase before**
- Case conversion of identifiers, protocols and types uses `Locale.ROOT`
  (`toLowerCase(Locale.ROOT)`); default-locale conversion breaks under `tr-TR`.
- Bookkeeping that must happen whatever the outcome (state flags, "running" markers, reports)
  goes in `finally`, so an `Error` cannot leave a source stuck.
- Resource names: beware `.` and `..` segments and percent-encoding when building paths or IRIs.
- Concurrent work runs on virtual threads (`Executors.newVirtualThreadPerTaskExecutor()`), is
  bounded (`Semaphore`, configured concurrency) and is shut down (`DisposableBean`).
- Caches are bounded or swept; a map keyed by tokens or user input must not grow forever.

**Observability**
- Logger: `private static final Logger LOG = LoggerFactory.getLogger(X.class)` or `@Slf4j`,
  whichever the file uses. Parameterized messages, no string concatenation, no `System.out`.
- `warn` for degraded but handled conditions, `error` only for failures that need an operator.
- Expose health through a `HealthIndicator` and counts or timings through Micrometer when the
  module already does so for similar work.

**GraalVM native images.** Code that needs reflection, resources or proxies at run time
registers them (`RuntimeHintsRegistrar` / `@ImportRuntimeHints`, as the existing modules do).
Consul client changes may need `consul-native` hints. CI builds the gateway natively.

**Style** (enforced by `checkstyles.xml` in the library modules and `service`; follow it in every
module)
- 4-space indent, 100-column lines, no tabs, no star imports.
- Imports in three groups separated by blank lines: `java.*`, `com.articulorium.*`, everything
  else; alphabetical within each.
- Abbreviations are written as words: `IiifController`, `OaiXmlWriter`, not `IIIFController`.
- Javadoc on every type and public method. The first sentence summarizes; later paragraphs in
  `<p>…</p>` say what the code guarantees and why (see `AccessVerifier`). Comments explain
  reasons, not mechanics.

**Persistence.** The platform's JPA schema is updated by Hibernate (`ddl-auto: update`) with no
migration tool: schema changes must be additive and safe on an existing database.

## Angular (ui and discovery-ui)

- Angular 22 standalone components (no `NgModule`), `inject()` rather than constructor
  parameters, signals (`signal`, `computed`, `effect`), `rxResource` for loading, and
  `ChangeDetectionStrategy.OnPush`.
- Selectors and CSS classes use the app prefix: `ui-` in `ui`, `disc-` in `discovery-ui`. Theme
  colours come from the existing CSS variables (`--ui-muted` …), not new literals.
- Import the Angular Material modules each component uses; use Material components before
  writing custom widgets.
- Tunables (poll intervals, page sizes) are `InjectionToken`s with a `providedIn: 'root'`
  factory so tests can override them.
- Every view handles loading, error (`HttpErrorResponse`, user message via `Notifier`) and empty
  states.
- **Text:** every user-visible string is an ngx-translate key added to `src/i18n/en.json` *and*
  all 12 locale files, with the same `{{placeholders}}` and the plural categories each language
  needs. Dates and numbers through the localized pipes / `Intl`. Layouts must work right-to-left
  (Arabic).
- **Security:** no `innerHTML` bindings or `bypassSecurityTrust*`; external links get
  `rel="noopener"`; the auth interceptor only sends tokens to configured service bases.
- **discovery-ui is server-rendered:** reach the page through `inject(DOCUMENT)` and the request
  through `inject(REQUEST, { optional: true })`, never `window`, `document` or `localStorage`
  directly. Keep its accessibility guarantees (landmarks, one `h1`, labelled fields, focus,
  `aria-current`).
- Formatting: Prettier (`singleQuote`, `trailingComma: all`), ESLint with Angular template
  accessibility rules.

## Tests

Every behaviour change and every bug fix comes with a test that fails without it.

**Java**
- JUnit 5 with AssertJ `assertThat`. Mockito only for collaborators that are not HTTP.
- HTTP clients: `MockRestServiceServer.bindTo(builder)` with request matchers; controllers:
  MockMvc; `@SpringBootTest` only when wiring itself is under test. Testcontainers tests must
  skip when Docker is absent.
- Test classes are package-private; method names are behaviour sentences in camelCase
  (`keepsTheCatalogOfEachCallerForItsLifetime`), no `test` prefix.
- Deterministic: a controllable `Clock` instead of sleeps; locale-sensitive code tested under
  `tr-TR` too; parameterized tests for tables of inputs.

**Angular (Vitest via `@angular/build:unit-test`)**
- `TestBed` with `provideHttpClient()`, `provideHttpClientTesting()` and the app's
  `provideTestConfig()`; `HttpTestingController.expectOne(...)` with method and URL, and
  `http.verify()` in `afterEach`.
- `describe('HarvestService')`, `it('lists the sources')`: sentences about behaviour.

**End to end (Cypress, `e2e/cypress/e2e/*.cy.ts`)**
- Runs against the compose stack through the gateway. A user flow that changes gets a spec or a
  new case in the existing spec for that page.
- Each spec starts with a comment saying what it covers. States the stack cannot produce (refusal,
  outage, failed harvest) are stubbed with `cy.intercept` using the bodies the real service sends.
- Specs create their own resources; they must not depend on run order.

## Docs, configuration and repository hygiene

- Behaviour that operators or API clients see is documented where they look: root `README.md`,
  the module README, `SECURITY.md`, compose files. Ports, routes and service lists must agree with
  the gateway and compose configuration; `docs/` (marketing site) must agree with the README.
- No scratch files, generated output or large binaries in commits (`Claude outputs/` is ignored).
- Commit subjects are imperative sentences about the behaviour ("End a harvest run whatever the
  harvester throws"), no `type:` prefixes; the body says why. One concern per commit.

## Verification

| What changed | Run |
|---|---|
| Anything Java | `mvn -B -ntp verify` (all modules, Angular builds, lint, unit tests) or `mvn -B -ntp -pl <module> -am verify` |
| Java without the front ends | `mvn -Dskip.npm verify` |
| `ui` / `discovery-ui` | `npm test`, `npm run lint`, `npm run i18n:check`, `npm run build` in the module |
| Translations or message bundles | `npm run i18n:check` (ui, discovery-ui) and the module's messages test (`MessageBundlesTest`, `PlatformMessagesTest` …) |
| Native-image–relevant code | `mvn -B -ntp -Pnative -pl gateway -am package -DskipTests` (or the module changed) |
| User flows | `docker compose up --build -d --wait` then `docker compose --profile e2e run --rm e2e` (`E2E_ARGS="--spec cypress/e2e/<x>.cy.ts"` for one) |
| Marketing site (`docs/`) | `node --test site-check/check-site.test.mjs` and `node site-check/check-site.mjs` |

## Reporting

For a review, list findings most severe first. Each finding has: file and line, one sentence
naming the defect, and a concrete failure scenario (input or state → wrong result). Separate what
you fixed (with commit) from follow-ups you left, and end with what was and was not verified.

## Red flags

`@Autowired` on a field · `Instant.now()` in logic · `toLowerCase()` without a locale ·
`startsWith` on request paths · `catch (Exception e) {}` · a `default` branch on an enum switch ·
`RestTemplate`/`WebClient` · `com.fasterxml.jackson.databind` · `System.out` · a token in a log
line · a hard-coded user-visible string · a key in `en.json` only · `innerHTML` · `window` in
discovery-ui · `Thread.sleep` in a test · a property in code but not in the README (or the reverse)
