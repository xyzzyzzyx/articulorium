---
name: articulorium
description: Architecture and patterns of Articulorium, a Linked Data Platform on Spring Boot microservices with Consul, Kafka and Angular UIs. Use when designing or placing a feature, adding a service capability or AI capability, or deciding how modules should talk to each other.
---

# Articulorium architecture and patterns

This skill says where things go and how the pieces fit. The other project skills, all in
`.claude/skills/` (read by both Claude Code and OpenCode), go with it and must not contradict it:

- **`code-quality`**: the quality bar, tests, verification and review format;
- **`spring-boot-best-practices`**: how to design Java changes in the services and starters;
- **`angular-best-practices`**: how to design changes in `ui` and `discovery-ui`.

## Platform

- Java 25, Spring Boot 4.1, Spring Cloud 2025.1, Spring AI 2.0, Jackson 3 (`tools.jackson.*`).
- Maven reactor (`pom.xml`) with libraries (`domain`, `event`, `activitystreams`, `agent`,
  `agent-spring-boot-starter`, `i18n-spring-boot-starter`, `consul-native`) and services
  (`platform`, `transform`, `index`, `triplestore`, `topology`, `oai`, `harvest`, `ai`,
  `discovery`, `service`, `gateway`); Angular front ends `ui` and `discovery-ui` (SSR).
- Dependency versions are managed once in the parent POM; modules do not pin their own.

## Microservices

- **Discovery and configuration:** Spring Cloud Consul for registration, discovery and shared
  key/value settings; `application.yml` defaults overridden by environment variables or Consul.
- **Calls between services:** `RestClient` through Spring Cloud LoadBalancer (Consul service
  names) where the module's `loadBalanced` setting is on.
- **Events:** Spring Cloud Bus over Kafka carries resource events from the platform; the `event`
  module's Spring Integration flows dispatch them to handlers in `transform`, `index` and
  `triplestore`.
- **Failure handling:** no circuit-breaker library. Clients use timeouts, bounded retries and
  RFC 9457 problem documents, and treat an unreachable dependency as "not allowed" or
  "unavailable".
- **Gateway:** routing, forwarded headers (resources are minted under the public address) and the
  combined Swagger UI. Access control is enforced by each service against the platform's WebAC,
  not by the gateway.

## Domain

- **Linked Data Platform** (with Fedora API extensions and archival groups) in `platform`.
- **OCFL storage** for resource content; JPA (Hibernate, `ddl-auto: update`, no migration tool)
  for platform state, so schema changes stay additive.
- **SPARQL store:** every resource mirrored into QLever (default), Blazegraph or Apache Jena
  Fuseki by `triplestore`.
- **Search index:** Solr or Elasticsearch, fed by `transform` (pluggable resource → document
  transformers) and `index`; `discovery` + `discovery-ui` serve the public search pages.
- **Structures:** communities, collections and PCDM topologies (`topology`); IIIF manifests and
  collections (`transform`); VIVO-style academic profiles.
- **Interoperability:** OAI-PMH provider (`oai`), harvesting of OAI-PMH/DSpace/Fedora/VIVO
  sources (`harvest`), COAR Notify / Linked Data Notifications inboxes (`fcr:inbox`).
- **Internationalization:** 12 shipped locales from one manifest
  (`i18n-spring-boot-starter/.../articulorium/locales.properties`), used by every service and both
  UIs, including right-to-left Arabic.

## AI agent (`ai`)

- Capabilities are `CapabilityAgent` implementations (see `Capability`); classification of
  harvested records into fields, disciplines, communities and collections is the main consumer.
- Models through Spring AI with Ollama or OpenAI-compatible providers, configured under
  `articulorium.ai`.
- Model calls are bounded by a fair `Semaphore` (`model.max-concurrency`); small local models'
  answers are parsed defensively.
- Every read of repository content goes through WebAC with the caller's credentials.
- Errors are RFC 9457 problem documents (`AgentExceptionHandler`).

## Security

- Web Access Control evaluated by the platform (`/.well-known/webac`), asked by each service with
  the caller's own `Authorization` header. `write` does not imply `read`.
- OpenID Connect access tokens (Keycloak realm on the gateway) validated with Nimbus JOSE + JWT:
  issuer, audience, algorithms.
- Secrets never in source control or packaged configuration; see `SECURITY.md` for the
  production checklist.

## Operations

- Health through `HealthIndicator`s (database, Kafka, search index, SPARQL store) and Micrometer
  metrics; harvest state on `/actuator/harvest`.
- Profiles: default (JVM) and `native` (GraalVM, `application-native.yml`); native-image hints
  via `RuntimeHintsRegistrar` and `consul-native`.
- Caches are time-bounded with an injected `Clock`, and only remember answers the authority gave.
- Compose overlays switch the search index (`docker-compose.elasticsearch.yml`) and SPARQL store
  (`docker-compose.blazegraph.yml`, `docker-compose.jena.yml`); CI runs the e2e suite against each.

## Placing a change

1. Find the owning module (table above); keep cross-module contracts in `domain` or `event`.
2. New settings go in that module's `@ConfigurationProperties` class under `articulorium.<module>`
   and in its README.
3. New user-visible text goes into every locale (`en.json` + 11 translations, or the module's
   `messages*.properties`).
4. New endpoints check WebAC, return problem documents, and appear in the gateway routes and
   README when public.
5. Then apply the `code-quality` skill: tests (unit, and e2e for user flows), verification,
   report.
