---
name: spring-boot-best-practices
description: How to build Spring Boot 4 features in Articulorium's services and starters — layering, controllers and OpenAPI, configuration and auto-configuration, RestClient calls, transactions and JPA, events, scheduling, actuator, native images and test slices. Use when writing or changing Java in any Maven module.
---

# Spring Boot best practices (Articulorium)

How to design Java changes. The `code-quality` skill holds the line-level rules (injection,
`final`, Javadoc, logging, problem documents, tests) and the verification commands; the
`articulorium` skill says which module owns what. Read the class next to the one you are adding
before you write it.

## Layering inside a service

`com.articulorium.<module>.` with packages by role:

| Package | Holds | Must not |
|---|---|---|
| `web` | `@RestController`s, the module's `@RestControllerAdvice`, access checks (`HarvestAccess`), actuator `@Endpoint`s | contain business rules or talk to stores directly |
| `service` | the use cases, `@Transactional` boundaries | know about HTTP types (`ResponseEntity`, headers) |
| `config` | `@ConfigurationProperties`, `@Configuration` classes building clients | hold logic beyond wiring |
| feature packages (`source`, `classification`, `iiif` …) | the domain logic of one feature, clients of one dependency | reach into another feature's internals |

Shared contracts between services go in `domain` (RDF model, vocabularies) or `event` (Bus
events); a service never depends on another service's module.

## Controllers and API design

- Thin: check access → call the service → return a record. Never return JPA entities.
- Declare `produces`/`consumes` on the mapping; JSON APIs use `MediaType.APPLICATION_JSON_VALUE`,
  LDP endpoints negotiate RDF.
- Status codes say what happened: `201` + `Location` for creation, `202` for work started in the
  background (a harvest), `204` for no body, problem documents for refusals.
- Credentials arrive as `@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false)
  @Nullable final String authorization` and are passed to the access check, which throws the
  module's access exception (mapped to 401/403/503).
- Optional APIs are switched with `@ConditionalOnBooleanProperty(name =
  "articulorium.<module>.api.enabled", matchIfMissing = true)`.
- Document with springdoc: `@Tag` on the controller, `@Operation(operationId = …, summary = …)`
  on each method, `@ApiResponse` for non-obvious statuses. A new public route also goes into the
  gateway routes, the combined Swagger UI list and the README.
- Validate at the boundary and refuse with the module's request exception (`reason = INVALID` →
  400) carrying a client-safe message; `@Valid` with Bean Validation is fine for request records.
- Log state-changing actions at `info` with who did it (`"{} harvest of {} started by {}"`),
  never with credentials.

## Configuration

- One `<Module>Properties` class per service, prefix `articulorium.<module>`, nested groups for
  `http`, `policy`, `access` …; durations as `Duration`, not raw numbers of seconds, for new
  settings.
- Derive values once in the constructor of the bean that uses them (normalize a base URL to end
  in `/`, compute lists) rather than on every call.
- Beans that are optional use `@ConditionalOnProperty` / `@ConditionalOnBooleanProperty`;
  defaults a deployment may replace use `@ConditionalOnMissingBean`.
- **Starters** (`agent-spring-boot-starter`, `i18n-spring-boot-starter`): `@AutoConfiguration`
  classes listed in `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`,
  every bean `@ConditionalOnMissingBean`, no component scanning, and a README section per setting.

## Calling other services and external sources

- Build `RestClient`s in a `@Configuration` with explicit connect and read timeouts (see
  `HarvestConfiguration`), qualified when a module has several (`DiscoveryClientConfiguration.SERVICES`).
- Build URIs with `UriComponentsBuilder` / encoded path segments, never by concatenating user
  input.
- Forward the caller's `Authorization` only to the platform and services that check WebAC; never
  to harvest sources or model providers.
- Map `RestClientResponseException` by status into the module's own exceptions; treat
  `RestClientException` (connection, timeout) as unavailable. Retries are bounded and only for
  idempotent requests.
- Parse remote JSON with Jackson 3 `JsonNode`/records and tolerate unknown fields; parse remote
  RDF with Jena and never trust remote IRIs as local paths.

## Persistence (platform)

- `@Transactional` on public service methods; `@Transactional(readOnly = true)` for reads. It
  has no effect on private methods or calls within the same class.
- No HTTP calls, event publishing that others act on, or long work inside a transaction; publish
  after commit.
- Spring Data repositories stay in the platform; fetch what a request needs in one query (watch
  for N+1 on collections).
- `ddl-auto: update` with no migration tool: add nullable columns and new tables only; a rename
  or type change needs a migration plan written down first.

## Events and background work

- Resource events travel on Spring Cloud Bus (Kafka); handlers are registered through the
  `event` module's Spring Integration flows. Handlers must be idempotent and tolerate events for
  resources that are already gone or changed again.
- Background work runs on virtual threads with an explicit bound (`Semaphore` or configured
  concurrency), records its own outcome in `finally`, and is single-flight where two runs would
  conflict (one harvest per source).
- Scheduled work uses cron expressions from configuration (`schedule`), checks whether it is
  enabled, and never overlaps itself.

## Operations

- Actuator exposure is an explicit list per service (`health,info,metrics,loggers`, plus what
  the service needs such as `integrationgraph`); never expose `env`/`configprops` publicly.
- A new external dependency gets a `HealthIndicator`; a new long-running job gets an actuator
  `@Endpoint` or metrics (see `HarvestEndpoint`).
- Request size limits are set deliberately (`spring.servlet.multipart.*`) per service.
- Problem details carry `Content-Language` and come from message bundles when users see them.

## Native images

- Avoid reflection, classpath scanning and dynamic proxies that AOT cannot see; when needed,
  register them with a `RuntimeHintsRegistrar` and `@ImportRuntimeHints`.
- Resources read at run time (`namespaces.yml`, `root.ttl`, i18n bundles) need resource hints.
- Check `application-native.yml` when a setting behaves differently in native mode.

## Testing slices

| Under test | Use |
|---|---|
| Pure logic, parsers, mappers | plain JUnit + AssertJ, no Spring |
| A client of another service | `MockRestServiceServer.bindTo(builder)` around the real client class |
| A controller and its advice | MockMvc (standalone or `@WebMvcTest`) with the service mocked |
| Wiring, properties binding, conditions | `ApplicationContextRunner` or `@SpringBootTest` |
| Repositories against PostgreSQL | Testcontainers, skipped without Docker |
| Time, caches, retries | the `Clock` constructor with a movable clock |

Every change to a status code, header or problem `reason` gets a controller test asserting it.
