![Java CI with Maven](https://github.com/xyzzyzzyx/articulorium/workflows/maven_build/badge.svg)

# Articulorium

A W3C [Linked Data Platform 1.0](https://www.w3.org/TR/ldp/) server built as Spring Boot
microservices on [Spring Cloud Consul](https://spring.io/projects/spring-cloud-consul) and
Spring Cloud Bus (Kafka), with Fedora Commons compatible extensions (versions, fixity, search,
archival groups, Web Access Control), an [OCFL](https://ocfl.io/) storage layer on the local file
system or any S3 compatible object store (MinIO by default), PostgreSQL for resource metadata, and
an Angular user interface at feature parity with the Fedora Commons web UI.

## Documentation

You can find the official documentation site at: [https://xyzzyzzyx.github.io/articulorium/](https://xyzzyzzyx.github.io/articulorium/)

The site is the static page in [docs/](docs/README.md). The `pages` workflow checks it on pull requests and
publishes it to GitHub Pages when changes are merged into `main`.

On top of plain LDP, Articulorium offers academic profiles described with the VIVO ontology and
digital library structures: communities, sub-communities
and collections; PCDM *topologies* (Book, Newspaper, Journal, Postcards, ... and custom ones) that
build and navigate the resources of a digital object; IIIF Presentation 3.0 export of those
structures; [COAR Notify](https://coar-notify.net/) inboxes for every container; and an
[OAI-PMH 2.0](https://www.openarchives.org/OAI/openarchivesprotocol.html) data provider for
harvesters; and a themeable, server-rendered public **discovery layer** (Angular SSR, Angular
Material, [Mirador](https://projectmirador.org/)) for searching and presenting the collections.
An optional AI agent ([Spring AI](https://docs.spring.io/spring-ai/reference/)) suggests
topologies, metadata, topics and profile details, and classifies harvested records, for people to
review.

Built on Java 25, Spring Boot 4.1 and Spring Cloud 2025.1.

For comprehensive documentation, please visit: [https://xyzzyzzyx.github.io/articulorium/](https://xyzzyzzyx.github.io/articulorium/)

| Module | Role |
| ------ | ---- |
| `domain`      | LDP resource model, vocabularies, IRI and N-Triples helpers (no framework dependencies) |
| `event`       | Spring Cloud Bus events (`ResourceEvent`, `DocumentEvent`) and the Spring Integration flows that dispatch them ([Event dispatch](#event-dispatch-spring-integration)) |
| `activitystreams` | W3C Activity Streams 2.0 types, object model, JSON serialization and validation, plain Java + Jackson ([activitystreams/README.md](activitystreams/README.md)) |
| `agent`       | Agent library: `Agent`, `AgentRegistry` and `AgentContributor` (components that act as agents while turned on), plain Java ([agent/README.md](agent/README.md)) |
| `agent-spring-boot-starter` | Spring Boot auto-configuration for the agent library: `AgentRegistry` bean, `articulorium.agents.*` settings, `/actuator/info` ([README](agent-spring-boot-starter/README.md)) |
| `i18n-spring-boot-starter` | Shipped locales, `articulorium.i18n.*` settings, locale resolution, message bundles and locale propagation for every service ([README](i18n-spring-boot-starter/README.md), [Internationalization](#internationalization)) |
| `consul-native` | GraalVM native image hints for the Spring Cloud Consul client; every service depends on it ([GraalVM native builds](GRAALVM_NATIVE_BUILDS.md#consul-client)) |
| `ui`          | Angular single page application, packaged as static resources ([ui/README.md](ui/README.md)) |
| `discovery`   | API of the public discovery layer: the deployment's branding, layouts, facets, filters and search precedence (`articulorium.discovery.*`), searches of the search index (Solr or Elasticsearch) with every hit checked against the platform's WebAC, resource descriptions with IIIF summaries, sitemap entries ([discovery/README.md](discovery/README.md)) |
| `discovery-ui` | The public discovery and presentation layer: Angular rendered on the server (Node.js) for search engines, themed Angular Material pages in every locale, Mirador for images, audio, video and PDFs ([discovery-ui/README.md](discovery-ui/README.md)) |
| `platform`    | The LDP server: RDF sources, containers, files, versions, fixity, search, OCFL storage  |
| `gateway`     | Spring Cloud Gateway; public entry point (`/ui/**`, `/platform/**`, `/transform/**`, `/topology/**`, `/auth/**` for the identity provider) and one Swagger UI for all services |
| `service`     | Serves the UI and its support API (settings, agents from `articulorium.agents.definitions`, media type detection with Apache Tika) |
| `transform`   | Turns resources into search documents with pluggable transformers; exports PCDM structures as IIIF ([transform/README.md](transform/README.md)) |
| `topology`    | PCDM topologies and building their resources in the platform ([topology/README.md](topology/README.md)) |
| `oai`         | OAI-PMH 2.0 data provider: records as Dublin Core, communities and collections as sets ([oai/README.md](oai/README.md)) |
| `harvest`     | Harvests OAI-PMH, DSpace, Fedora Commons and VIVO repositories into the platform; source-independent harvest and ingest abstractions ([harvest/README.md](harvest/README.md)) |
| `ai`          | Optional Spring AI agent: topology suggestions, metadata of images, videos and PDFs, topic models of communities and collections, academic profile suggestions, classification of harvested records, all reviewed before they are applied; each capability is an `AgentContributor` of the agent registry ([ai/README.md](ai/README.md)) |
| `index`       | Keeps the search index in sync: a Solr collection or an Elasticsearch index ([Search index](#search-index-solr-or-elasticsearch)) |
| `triplestore` | Mirrors every resource into a SPARQL 1.1 store, one named graph each: QLever, or Blazegraph or Apache Jena Fuseki ([SPARQL store](#sparql-store-qlever-blazegraph-or-apache-jena)) |

```
browser ──> gateway ──> service (UI)
               ├─────> discovery-ui (/discover: SSR pages) ──> gateway ──> discovery (/discover/api) ──> Solr or Elasticsearch
               │                                                              └──(WebAC, IIIF)──> platform, transform
               ├─────> keycloak (/auth: OpenID Connect login, tokens)
               ├─────> topology ───────────────(LDP)──┐
               ├─────> transform (IIIF) ───────(LDP)──┤
               ├─────> oai (OAI-PMH) ──────────(LDP)──┤
               │  harvest (other repositories) (LDP)──┤
               └─────> platform <─────────────────────┘
                          │   └──(resource-bus)──> transform ──(document-bus)──> index ──> Solr or Elasticsearch
                          │   └──(resource-bus)──> triplestore ──> QLever, Blazegraph or Jena
                          └──> PostgreSQL (metadata, ACLs, notifications)   OCFL on MinIO/S3 or disk (content, versions)
```

## Requirements

* Java 25 and Maven 3.9 (or just Docker); the `ui` module downloads its own Node.js
* Docker with Compose v2 for the full stack
* For native images: GraalVM for JDK 25 (or Docker, which uses a GraalVM build image)

## Running

```bash
docker compose up --build
```

Then open <http://localhost:8000/> in a browser for the user interface: browse and search the
repository, create containers and archival groups, upload files, edit properties with SPARQL
Update, create and view versions, manage permissions, build community structures and PCDM
topologies, create and edit academic profiles (VIVO), read and send notifications and open IIIF manifests.
<http://localhost:8000/platform/> is the LDP root itself, serving Turtle (default) or any other
RDF syntax to RDF clients and a simple HTML view to browsers.

| URL                                  | What                                   |
|--------------------------------------|----------------------------------------|
| http://localhost:8000/ui/            | User interface                         |
| http://localhost:8000/discover/      | Public discovery layer (search, collections, resource pages with Mirador) |
| http://localhost:8000/platform/      | LDP root through the gateway           |
| http://localhost:8000/swagger-ui.html | Swagger UI for the platform, transform, topology and service APIs (the UI's "API" link) |
| http://localhost:8000/auth/          | Keycloak (realm `articulorium`; admin console `/auth/admin/`, `admin` / `admin`) |
| http://localhost:8000/topology/topologies | PCDM topologies                   |
| http://localhost:8000/transform/iiif/communities | IIIF Collection of the community structure |
| http://localhost:8000/oai?verb=Identify | OAI-PMH base URL (`http://localhost:8000/oai`) |
| http://localhost:9001/               | LDP root directly on the platform      |
| localhost:5432                       | PostgreSQL (`articulorium` / `articulorium`) |
| http://localhost:8500/            | Consul UI                              |
| http://localhost:9101/            | MinIO console (`articulorium` / `articulorium-secret`) |
| http://localhost:8983/            | Solr admin                             |
| http://localhost:7001/            | QLever SPARQL endpoint (updates need `QLEVER_ACCESS_TOKEN`) |

`docker compose --profile tools up` also starts a Kafka UI on port 9091.
`docker compose -f docker-compose.yml -f docker-compose.acl.yml up` turns on Consul ACLs and
accepts Consul tokens as identities (see [Access control](#access-control-webac)).
**Sign in** in the user interface logs in with Keycloak (see [Authentication](#authentication)).

### Running the platform alone

```bash
mvn -pl platform -am package -DskipTests
java -jar platform/target/platform-1.0.0-SNAPSHOT.jar \
  --spring.cloud.consul.enabled=false --spring.cloud.bus.enabled=false \
  --articulorium.storage.type=filesystem \
  --spring.datasource.url=jdbc:h2:file:./data/db/articulorium   # or a PostgreSQL URL
```

## Using the LDP API

```bash
P=http://localhost:8000/platform

# a basic container
curl -i -X POST $P/ -H 'Slug: books' -H 'Content-Type: text/turtle' \
     --data '<> <http://purl.org/dc/terms/title> "Books" .'

# an RDF source (not a container)
curl -i -X POST $P/books -H 'Content-Type: text/turtle' \
     -H 'Link: <http://www.w3.org/ns/ldp#RDFSource>; rel="type"' \
     --data '<> <http://purl.org/dc/terms/title> "Moby Dick" .'

# upload a file: raw body ...
curl -i -X POST $P/books -H 'Slug: cover.png' -H 'Content-Type: image/png' --data-binary @cover.png
# ... or multipart
curl -i -X POST $P/books -F file=@report.pdf

# its description (an RDF source) and the bytes
curl $P/books/report.pdf/description
curl -O $P/books/report.pdf

# replace (with optimistic locking), patch, delete
curl -i -X PUT $P/books -H 'Content-Type: text/turtle' -H 'If-Match: "<etag>"' --data '...'
curl -i -X PATCH $P/books -H 'Content-Type: application/sparql-update' \
     --data 'INSERT DATA { <> <http://purl.org/dc/terms/creator> "Ishmael" }'
curl -i -X DELETE $P/books
```

### LDP support

* **Interaction models**: `ldp:RDFSource`, `ldp:BasicContainer`, `ldp:DirectContainer`,
  `ldp:IndirectContainer`, `ldp:NonRDFSource`, chosen with `Link: <…>; rel="type"` or inferred
  from the body. Models cannot be changed afterwards.
* **Methods**: GET, HEAD, OPTIONS, POST (with `Slug`), PUT (replace or create), PATCH
  (`application/sparql-update`), DELETE (recursive for containers).
* **Headers**: `Link rel="type"` for every LDP class, `Link rel="describedby"/"describes"` between
  files and their descriptions, `Link rel="ldp#constrainedBy"` →
  [`/.well-known/ldp-constraints`](platform/src/main/resources/ldp-constraints.txt), strong `ETag`,
  `If-Match`/`If-None-Match` (412/304), `Allow`, `Accept-Post`, `Accept-Patch`, `Location`,
  `Prefer` (`PreferMinimalContainer`, `PreferContainment`, `PreferMembership`) with
  `Preference-Applied`, `Digest` and `Range` for files.
* **Server-managed triples**: `ldp:contains`, membership triples, LDP types and file metadata
  (`premis:hasSize`, `premis:hasMessageDigest`, `ebucore:hasMimeType`, `ebucore:filename`) are
  computed on every request and protected from modification (409 + `constrainedBy`).
* **Membership**: direct (`ldp:hasMemberRelation` / `ldp:isMemberOfRelation`) and indirect
  (`ldp:insertedContentRelation`) containers; `ldp:membershipResource` defaults to the
  container and the relation to `ldp:member`.
* **Formats**: Turtle (default), JSON-LD, N-Triples, RDF/XML, RDF/JSON, N3; HTML for browsers.
  RDF-only clients asking for a file are redirected (303) to its description.
* **Proxies**: `X-Forwarded-*` headers (set by the gateway) determine resource IRIs; IRIs are
  stored relative to the server base, so the same data is valid under any host name.

### Fedora API extensions

| Request                               | What                                                       |
|---------------------------------------|------------------------------------------------------------|
| `GET <r>/fcr:versions`                | Versions as JSON, or a Memento TimeMap (`application/link-format`) |
| `POST <r>/fcr:versions`               | Record the current state as a version (201 + `Location`)  |
| `GET <r>/fcr:versions/<id>`           | A historic version: the bytes of a file, or RDF            |
| `GET <file>/fcr:fixity`               | Re-hash the stored bytes and validate the OCFL object      |
| `GET /fcr:search?condition=…`         | Search on `fedora_id`, `created`, `modified`, `content_size`, `mime_type`, `rdf_type` with `=`, `<`, `<=`, `>`, `>=` and `*` wildcards; `max_results`, `offset`, `order_by`, `order`; hits the agent may not read are left out |
| `GET/PUT/PATCH/DELETE <r>/fcr:acl`    | The resource's WebAC access control list (see [Access control](#access-control-webac)) |
| `GET/POST <container>/fcr:inbox`      | The container's COAR Notify inbox; `GET <container>/fcr:inbox/<id>` one notification (`application/ld+json`, or `application/activity+json` on request) |
| `GET/POST <container>/fcr:outbox`     | Notifications sent on behalf of the container; POST sends one |
| `GET /.well-known/webac?resource=<r>` | The requesting agent, its access modes on `<r>` and (with `acl:Control`) the effective authorizations |

Every file write is a new version automatically. Mementos of files describe the bytes of their
version: each version records the file's technical metadata (size, SHA-256 digest, media type and
file name, `technical.nt` in the OCFL object), which the memento's RDF view states and whose media
type the memento's bytes are served with. The 25 namespace prefixes Fedora Commons
declares by default (plus PCDM, ORE, SKOS, schema.org and others) are pre-registered
([namespaces.yml](platform/src/main/resources/namespaces.yml)) and used in every RDF response.

### Archival groups

A container created with `Link: <http://fedora.info/definitions/v4/repository#ArchivalGroup>;
rel="type"` (or with the "Archival group" box of the UI's create form) is an *archival group*, as
in Fedora 6: it and everything later created inside it are stored in **one OCFL object** and
versioned as a unit.

* The group is typed `fedora:ArchivalGroup` (header and body) and has a first version from the
  start; members link to it with `Link: <group>; rel="https://articulorium.org/ns/repository#archivalGroup"`.
* Inside the object every member keeps its files in its own directory (`members/<name>/…`).
  Every file write inside the group is a version of the group; `POST <group>/fcr:versions`
  snapshots the descriptions of the group and every member.
* `GET <member>/fcr:versions` lists the group versions that contain the member; members cannot be
  versioned on their own (`POST` answers 405). Deleting a member writes a group version without
  its files; deleting the group purges the object.
* Archival groups cannot be nested, and only containers can be archival groups (409 otherwise).

## Authentication

Users sign in with an **OpenID Connect** provider; [Keycloak](https://www.keycloak.org/) is the
default and runs in the compose stack. Nothing outside configuration is specific to Keycloak:
another OpenID Connect / OAuth 2.0 provider (Entra ID, Auth0, Okta, Dex, ...) replaces it by
changing the settings below.

**Login flow.** **Sign in** in the user interface redirects to the provider's login page
(authorization code flow with PKCE, public client `articulorium-ui`), which redirects back to the
page sign-in started from. The UI keeps the tokens in the tab's session storage, renews the access
token with the refresh token before it expires, and sends it as `Authorization: Bearer <jwt>` to
the platform, topology and transform services (the topology service passes it on to the
platform). The platform validates the token itself (signature with the issuer's key set,
`iss`, `exp`, `aud`) and maps it to a WebAC agent: `info:oidc/agent/<preferred_username>`, with
the realm roles and groups as `acl:agentGroup`s `info:oidc/group/<name>`. **Sign out** ends the
session at the provider too.

**Docker Compose.** `keycloak` (development mode) imports
[keycloak/articulorium-realm.json](keycloak/articulorium-realm.json) on a fresh start and is
reached through the gateway at <http://localhost:8000/auth/>, so the UI, the API and the login
pages share one origin and the issuer is `http://localhost:8000/auth/realms/articulorium` for
browsers and tokens alike; the platform fetches the realm's keys from `keycloak:8080` instead.
The realm defines the roles `editors` and `administrators`, an audience mapper (`aud:
articulorium`) and three **demo users for local use only**:

| User            | Password        | Realm roles                  |
|-----------------|-----------------|------------------------------|
| `curator`       | `curator`       | `editors`                    |
| `administrator` | `administrator` | `editors`, `administrators`  |
| `reader`        | `reader`        | (none)                       |

The stack keeps the open default root ACL, so signing in only changes who is recorded as
`fedora:createdBy`. For role based access set `ARTICULORIUM_WEBAC_ROOT_ACL:
classpath:webac/root-acl-oidc.ttl` on the platform
([root-acl-oidc.ttl](platform/src/main/resources/webac/root-acl-oidc.ttl): everyone reads,
`editors` write, `administrators` also control ACLs).

| Variable                      | Default                         | Where              |
|-------------------------------|---------------------------------|--------------------|
| `KEYCLOAK_PUBLIC_URL`         | `http://localhost:8000/auth`    | Keycloak's `KC_HOSTNAME` and the platform's issuer; change it together with the client's redirect URIs in the realm when the stack is reached at another address |
| `KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` | `admin` / `admin` | Keycloak's bootstrap administrator |

**Settings.** Platform (`articulorium.webac.oidc.*`, see
[application.yml](platform/src/main/resources/application.yml)):

| Property          | Default (Keycloak realm of the stack)                      | Meaning |
|-------------------|------------------------------------------------------------|---------|
| `enabled`         | `true`                                                     | accept JWT access tokens |
| `issuer-uri`      | `${KEYCLOAK_PUBLIC_URL}/realms/articulorium`              | required `iss` |
| `jwk-set-uri`     | `${KEYCLOAK_INTERNAL_URL}/realms/articulorium/protocol/openid-connect/certs` | key set; empty discovers it from `<issuer>/.well-known/openid-configuration` |
| `audiences`       | `articulorium`                                             | accepted `aud` values (empty accepts any) |
| `jws-algorithms`  | `RS256`                                                    | accepted signature algorithms |
| `agent-claims`    | `preferred_username,sub`                                   | first present claim names the agent |
| `group-claims`    | `realm_access.roles,groups`                                | claims (dotted paths) listing groups or roles |
| `agent-prefix` / `group-prefix` | `info:oidc/agent/` / `info:oidc/group/`      | prefixes of the minted IRIs |
| `clock-skew`      | `60s`                                                      | tolerated clock difference |

User interface (service, `articulorium.ui.auth.*`): `provider` is `oidc` (default), `token` (paste
a token: the Consul ACL tokens of `docker-compose.acl.yml`) or `none`; `oidc.authority` (default
`../auth/realms/articulorium`, relative to the UI), `oidc.client-id` (`articulorium-ui`) and
`oidc.scope` (`openid profile`). Register the UI with another provider as a public client with
PKCE, redirect URI `<ui>/` (and `<ui>/*`), post-logout redirect URI `<ui>/*`, and make its access
tokens carry the configured audience.

**Provider abstraction.** The platform asks every `AgentAuthenticationProvider` bean in order;
the first that recognises the request's credentials yields the agent, and invalid credentials end
the request with 401:

1. `OidcAuthenticationProvider`: JWT bearer tokens of the configured issuer (OpenID Connect and
   OAuth 2.0 providers issuing JWT access tokens).
2. `ConsulTokenAuthenticationProvider`: other bearer tokens and `X-Consul-Token`, when
   `articulorium.webac.consul.enabled`.
3. `TrustedHeaderAuthenticationProvider`: `articulorium.webac.trusted-agent-header`, set by an
   authenticating reverse proxy. This is the way in for **SAML 2.0** (a service provider such as
   Shibboleth or mod_auth_mellon in front of the platform) and other server-side login flows;
   alternatively Keycloak brokers SAML (and social OAuth 2.0) identity providers into the
   `articulorium` realm, and nothing in Articulorium changes.
4. `ContainerPrincipalAuthenticationProvider`: a principal authenticated by the servlet container.

Another provider is a Spring bean implementing `AgentAuthenticationProvider` with an `@Order`;
it must only return agents and group IRIs. The user interface has the same seam
(`AuthProvider` in `ui/src/app/core/auth`: `OidcAuthProvider`, `TokenAuthProvider`,
`NoAuthProvider`).

## Access control (WebAC)

Every request to the platform is checked against [Web Access Control](https://solidproject.org/TR/wac)
authorizations (`articulorium.webac.enabled`, default `true`).

* **Effective ACL**: the resource's own ACL (`<r>/fcr:acl`, Turtle or any RDF syntax), else the
  nearest ancestor's (only its `acl:default` authorizations apply), else the root ACL configured
  with `articulorium.webac.root-acl` ([default](platform/src/main/resources/webac/root-acl.ttl):
  everyone may do everything, so a fresh repository works without credentials).
* **Modes**: GET/HEAD need `acl:Read`, POST `acl:Append`, PUT/PATCH/DELETE `acl:Write` (which
  implies Append), anything on `fcr:acl` `acl:Control`. Authorizations name agents
  (`acl:agent`), everyone (`acl:agentClass foaf:Agent`), authenticated agents
  (`acl:AuthenticatedAgent`), groups (`acl:agentGroup`: a `vcard:Group` resource listing members
  with `vcard:hasMember`, or a group the agent's credentials put it in) and resource types
  (`acl:accessToClass`).
* Denied requests get 401 (anonymous, with `WWW-Authenticate: Bearer`) or 403; every response
  carries `WAC-Allow: user="…",public="…"` and `Link: <r/fcr:acl>; rel="acl"`. The agent is
  recorded as `fedora:createdBy`/`lastModifiedBy` and as author of OCFL versions.
* The UI shows your access on every resource, hides actions you may not take, and edits ACLs on
  the **Permissions** page of any resource.

**Agents.** Agents come from the [authentication providers](#authentication): OpenID Connect
access tokens (Keycloak by default) and, with `articulorium.webac.consul.enabled=true`, **Consul ACL tokens**
(`Authorization: Bearer <secret id>` or `X-Consul-Token`) and validates them with Consul
(`/v1/acl/token/self`, cached for `cache-ttl`). The token's description (or accessor ID) becomes the
agent `info:consul/agent/<description>`; its roles and policies become the groups
`info:consul/role/<name>` and `info:consul/policy/<name>`. Alternatively
`articulorium.webac.trusted-agent-header` names a header set by a trusted authenticating proxy, and
a principal authenticated by the servlet container is used as is.

`docker-compose.acl.yml` sets this up: Consul runs with ACLs, the UI's **Sign in** asks for a
token instead of redirecting to Keycloak, the platform accepts tokens and uses
[root-acl-consul.ttl](platform/src/main/resources/webac/root-acl-consul.ttl) (everyone reads, role
`editors` writes, role `administrators` also controls ACLs). Create a role and a token, then paste
the token's `SecretID` into the UI's **Sign in** dialog:

```bash
export CONSUL_HTTP_TOKEN=articulorium-consul-management     # CONSUL_MANAGEMENT_TOKEN
docker compose exec -e CONSUL_HTTP_TOKEN consul consul acl policy create -name articulorium-read \
  -rules 'key_prefix "" { policy = "read" }'
docker compose exec -e CONSUL_HTTP_TOKEN consul consul acl role create -name editors -policy-name articulorium-read
docker compose exec -e CONSUL_HTTP_TOKEN consul consul acl token create -description "Jane Doe" -role-name editors
```

## Communities, sub-communities and collections

The platform creates `/communities` (`articulorium.ldp.communities-path`, typed
`art:CommunityRoot`) on start-up. Below it, communities are containers typed `art:Community`
(a community inside a community is a sub-community) and collections containers typed
`art:Collection` and `pcdm:Collection` (`art:` is `https://articulorium.org/ns/repository#`).
Everything is plain LDP, so it can be created with any LDP client; the UI's **Communities** page
navigates the structure, creates communities, sub-communities and collections, and builds items
in collections from topologies.

## Academic profiles (VIVO)

The platform creates `/profiles` (`articulorium.ldp.profiles-path`, typed `art:ProfileRoot`) on
start-up. Each academic profile is one basic container below it, described with the
[VIVO ontology](https://wiki.lyrasis.org/display/VIVODOC/VIVO+Ontology) (`vivo:` and `obo:` are
registered prefixes): the resource itself is the `foaf:Person` (optionally `vivo:FacultyMember`,
`vivo:NonFacultyAcademic`, `vivo:EmeritusProfessor`, `vivo:Librarian`, `vivo:Student` or
`vivo:NonAcademic`), and every individual VIVO relates it to is a hash IRI of the profile:

| Part                | Modelled as                                                                     |
|---------------------|---------------------------------------------------------------------------------|
| Name, title, email, telephone, websites | `obo:ARG_2000028` → `<#vcard>` (`vcard:Individual`) with `vcard:Name` (`vcard:givenName`, `vivo:middleName`, `vcard:familyName`), `vcard:Title`, `vcard:Email`, `vcard:Telephone`, ranked `vcard:URL`s; `rdfs:label` is "Family, Given" |
| Overview, ORCID iD, keywords | `vivo:overview`, `vivo:orcidId <https://orcid.org/…>`, `vivo:freetextKeyword` |
| Research areas      | `vivo:hasResearchArea` → `skos:Concept`s with an `rdfs:label`                   |
| Positions           | `vivo:relatedBy` → `vivo:FacultyPosition` (or another `vivo:Position`) relating the person and a `foaf:Organization`, with a `vivo:DateTimeInterval` of year-precision `vivo:DateTimeValue`s |
| Education           | `obo:RO_0000056` → `vivo:EducationalProcess` (`vivo:majorField`, the organization as participant, end year) whose output is a `vivo:AwardedDegree` |
| Publications        | `vivo:relatedBy` → ranked `vivo:Authorship` relating the person and a `bibo:Document` (`bibo:AcademicArticle`, `bibo:Book`, ...) with `bibo:doi`, a publication year and a `vivo:hasPublicationVenue` |

The UI's **Profiles** page lists them; a profile page shows one and its editor creates and edits
them with validation (required names, email and telephone formats, ORCID check digit, web
addresses, four-digit years with end not before start, DOIs). A profile is saved as a whole: a
`POST` in the profile container, then `PUT` with `If-Match`, so a profile changed by someone else
after it was opened is never overwritten (412). The editor owns the parts in the table and
regenerates them on save; any other statement (other properties of the person, relationships to
resources elsewhere, related individuals of other kinds) is kept as it is. Being plain LDP, profiles
are versioned, access controlled, indexed (`rdfs:label`) and mirrored into the SPARQL store like
any other resource.

## Topologies (PCDM structures)

The topology service ([topology/README.md](topology/README.md)) knows the shape of kinds of
digital objects as trees of PCDM collections and objects with metadata fields, file uses and child
counts. Built in: **Book** (pages), **Newspaper** (issues of pages), **Journal** (volumes, issues,
articles), **Postcards** (front and back), **Manuscript** (folios with recto and verso) and
**Audiovisual recording** (parts). Custom topologies are created in the UI (**Topologies**) or the
API and stored in the repository (`/topologies/<id>`).

Building a topology creates the PCDM resources in the platform: a container per node typed
`pcdm:Object`/`pcdm:Collection` plus the node's types, `members` (`pcdm:hasMember`) and `files`
(`pcdm:hasFile`) direct containers, `schema:position` ordering, default metadata, and
`art:topology`/`art:topologyNode` links back to the definition. The resource page's **Topology**
aside then shows the node, its parent and ordered members, adds children, edits the node's metadata
fields and uploads files per PCDM use; on any other container it builds a topology.

## IIIF

`GET /transform/iiif/<path>` exports a PCDM object as a IIIF Presentation 3.0 **Manifest** (one
canvas per leaf object in `schema:position` order, painting its image, audio or video file with
probed image dimensions, other files as `rendering`, intermediate objects as `structures` ranges,
every descriptive property as `metadata`, links to the LDP resource and the UI page) and a PCDM
collection or community as a **Collection** of manifests and collections. The UI links each
exportable resource's export ("IIIF").

A canvas without an image, audio or video file is painted with its PDF, if it has one, as a `Text`
body with format `application/pdf` (`articulorium.iiif.paint-documents`, default `true`), so viewers
that present documents (the discovery layer's Mirador) show it; other files stay renderings.

## Discovery layer

`http://localhost:8000/discover/` is the public face of the repository: search with facets and
filters, the community structure, and a page per community, collection and object that presents its
IIIF export in Mirador (deep zoom images, audio, video, PDFs). Pages are rendered on the server
(Angular SSR) with titles, descriptions, canonical URLs, `hreflang` alternates for every locale,
Open Graph and Twitter cards and schema.org JSON-LD; `/discover/sitemap.xml` and
`/discover/robots.txt` are generated from the index. Only resources anonymous visitors may read
(the platform's WebAC) are listed or shown.

Everything a deployment changes is configuration of the `discovery` service
(`articulorium.discovery.*`, in `application.yml`, environment variables or Consul): the site's name,
logo and texts in every language, Material 3 colors, fonts, shape, density and extra CSS, the order
of the home page's sections, list or grid results, facet position, the viewer's position and size,
the metadata fields of resource pages, the search fields and their boosts, facets, filters, sort
orders and search engine directives. See [discovery/README.md](discovery/README.md) for the settings
and [discovery-ui/README.md](discovery-ui/README.md) for the application.

Set `DISCOVERY_PUBLIC_URL` to the address visitors use (canonical URLs and sitemaps are built from
it) and `GATEWAY_ROOT_REDIRECT=/discover/` to make the discovery layer the gateway's home page.

## Search index (Solr or Elasticsearch)

The index service writes the transform service's search documents to a search index, and the
discovery service searches it. The index is a Solr collection unless configured otherwise;
Elasticsearch can be used instead. Both services must name the same backend:

| Service   | Setting (environment variable)                                                  | Default |
|-----------|----------------------------------------------------------------------------------|---------|
| index     | `articulorium.index.backend` (`ARTICULORIUM_INDEX_BACKEND`)                      | `solr`  |
| discovery | `articulorium.discovery.index.backend` (`ARTICULORIUM_DISCOVERY_INDEX_BACKEND`)  | `solr`  |

The values are `solr` and `elasticsearch`; any other value stops the service at start-up. Solr
deployments need no change: without these settings both services work as before, with
`articulorium.solr.*` and `articulorium.discovery.solr.*`. The backend is read when a service
starts, in GraalVM native images too.

### Elasticsearch

```bash
docker compose -f docker-compose.yml -f docker-compose.elasticsearch.yml up --build
```

starts the stack with a single Elasticsearch node (no security: development and tests only) as its
search index. For a cluster of your own:

| Setting | Default | Meaning |
|---|---|---|
| `articulorium.elasticsearch.base-url` (index), `articulorium.discovery.elasticsearch.base-url` | `http://elasticsearch:9200` | a node, or a load balancer in front of the cluster |
| `articulorium.elasticsearch.index`, `articulorium.discovery.elasticsearch.index` | `articulorium` | the index (or an alias of one index) |
| `...elasticsearch.api-key` | (none) | an API key, sent as `Authorization: ApiKey <key>`: the Base64 "encoded" value Elasticsearch returns when the key is created |
| `...elasticsearch.username`, `...elasticsearch.password` | (none) | HTTP basic authentication, when no API key is set |
| `articulorium.elasticsearch.number-of-shards`, `number-of-replicas`, `refresh-interval` | `1`, `1`, `1s` | of an index the index service creates (`number-of-replicas: 0` on a single node); the refresh interval is how soon changes are searchable, like Solr's `commit-within` |
| `articulorium.elasticsearch.fields` | `created`, `lastModified`, `date`: `date` | fields declared in the mapping on start-up when missing, by name, with their Elasticsearch type |

Give credentials as environment variables (`ARTICULORIUM_ELASTICSEARCH_API_KEY`,
`ARTICULORIUM_DISCOVERY_ELASTICSEARCH_API_KEY`) or Consul keys rather than in files. The index
service creates the index and changes its mapping, and writes and counts documents (the `all`
privilege on the index is the simplest grant); the discovery service only needs `read`. `https`
base URLs work with certificates the JVM trusts; add a private certificate authority to the JVM's
trust store. Neither service uses an Elasticsearch client library: they only call REST APIs that
Elasticsearch 8 and 9 share.

When the index does not exist, the index service creates it with mappings that give fields the
names and behaviour they have in Solr's `_default` config set, so **documents and the discovery
layer's settings are the same for both backends**:

- `<field>` is full text; its values are copied to `<field>_str` (exact values, for facets, filters
  and sorting) and to `_text_` (everything, the fallback search field);
- `<field>_txt_<language>` is full text with that language's analyzer;
- `id` is the exact document identifier;
- the fields of `articulorium.elasticsearch.fields` are declared with their types. Dates are not
  detected from values: a field the discovery layer sorts on or builds a year facet from must be
  declared as `date` there (a value that is not a date is then left out instead of failing its
  document).

An index (or alias) that already exists is used as it is: only missing declared fields are added.

Base filters, filter queries and boost queries of the discovery settings are written in the Lucene
query syntax both backends read (`type_str:("a" OR "b")`, `-id:*\/members\/*`, `hasFile_str:*`);
Solr local parameters (`{!...}`) only work with Solr. What differs:

| | Solr | Elasticsearch |
|---|---|---|
| Search terms | `edismax` parser | `simple_query_string` query: the same fields, boosts, phrase boosts and minimum match; operators and ranking differ in details |
| `<field>_str` copies | the first 256 characters of a value | whole values; values beyond 8191 characters are left out |
| Fields without a document yet | cannot be searched, sorted or faceted on until declared | are ignored |
| Deep paging | any depth | the first 10,000 results: `articulorium.discovery.seo.sitemap-limit` above 10,000 has no effect |
| Japanese | morphological analysis | bigrams (`cjk` analyzer), unless you map `*_txt_ja` to the `kuromoji` plugin's analyzer in an index you create yourself |

### Moving a deployment from Solr to Elasticsearch

1. Start Elasticsearch and give the index service `ARTICULORIUM_INDEX_BACKEND=elasticsearch` and
   its `articulorium.elasticsearch.*` settings. On start-up it creates the index and reports it as
   the `elasticsearch` health component (instead of `solr`).
2. The new index is empty. Documents are written as resources are created and changed; nothing is
   copied from Solr and there is no command to index existing resources again yet, so resources
   reach Elasticsearch when they are next modified. Keep Solr and the discovery service on Solr
   until the index holds what the discovery layer should show (compare the `documents` counts of
   the health components).
3. Switch the discovery service (`ARTICULORIUM_DISCOVERY_INDEX_BACKEND=elasticsearch` and its
   `articulorium.discovery.elasticsearch.*` settings), check a search, a facet selection, a sort
   order and a resource page, then retire Solr and ZooKeeper.
4. Custom settings: declare the date fields of your own sorts and year facets in
   `articulorium.elasticsearch.fields` (the counterpart of `articulorium.solr.fields`), and rewrite
   filter or boost queries that use Solr local parameters.

Going back is the same in reverse; Solr keeps whatever it held.

## Notifications (COAR Notify)

Every container (community, collection, ...) has a [Linked Data Notifications](https://www.w3.org/TR/ldn/)
inbox advertised with `Link: <container/fcr:inbox>; rel="http://www.w3.org/ns/ldp#inbox"`. POSTing
a COAR Notify notification (`application/ld+json` or `application/activity+json`; an Activity
Streams activity such as `Offer` or `Announce` with a COAR Notify type such as
`coar-notify:ReviewAction`, `origin`, `target` and `object`) stores it after validation; GET lists
the inbox (JSON summaries for
`application/json`, an `ldp:contains` listing for RDF). `POST <container>/fcr:outbox` sends a
notification on behalf of the container, filling in `id`, contexts and `origin`, and records
whether the target inbox accepted it. The UI's **Notifications** page of a container shows both and
sends notifications for the common patterns (request review or endorsement, announce review,
endorsement, relationship or ingest, accept, reject, undo).

Notifications are checked twice: against COAR Notify (contexts, `id`, activity type, `origin` and
`target` services, `object`) and against [Activity Streams 2.0](https://www.w3.org/TR/activitystreams-core/)
with the [activitystreams](activitystreams/README.md) library (IRIs, date-times, the ranges of
vocabulary properties, nested objects). Problems are answered with 400 and one line per problem
(`published must be an RFC 3339 date-time with a time zone offset`); malformed JSON, duplicate
property names and non-object documents are 400 as well, other media types 415 with `Accept-Post`.
As `application/activity+json` the Activity Streams context is implied (Activity Streams 2.0,
section 2.1) and stated, first, in the stored notification; `application/ld+json` documents must
state it.

## OAI-PMH

The oai service ([oai/README.md](oai/README.md)) is an OAI-PMH 2.0 data provider at
`http://localhost:8000/oai`: all six verbs, unqualified Dublin Core (`oai_dc`) mapped from each
record's RDF (configurable per element), communities and collections as hierarchical sets
(`arts:paintings`), `from`/`until` harvesting at day or second granularity and stateless
resumption tokens. Records are the PCDM objects of the repository (a book, not its pages),
identified as `oai:articulorium.org:<path>` and linked to their UI page and LDP resource. The
harvester's credentials are passed to the platform, so Web Access Control decides what each
harvester sees. Deleted records are not reported (`deletedRecord=no`).

```bash
curl 'http://localhost:8000/oai?verb=ListRecords&metadataPrefix=oai_dc&from=2026-01-01'
```

## Harvesting

The harvest service ([harvest/README.md](harvest/README.md)) harvests other repositories into the
platform: any OAI-PMH data provider, DSpace 7+ (REST API), Fedora Commons 6 (and other Articulorium
instances) and VIVO (SPARQL Query API). Sources are configured under
`articulorium.harvest.sources`; their records are mapped to one shared representation (RDF
metadata, sets, file references) and stored at `/harvested/<source>/<record>` with their
provenance, replaced on every harvest. Paging, incremental harvests from a checkpoint kept on the
source's container, retries and failure reports are common to every source; a checkpoint only moves
after a harvest without failures, so no record is skipped. Harvests run on per-source schedules or
on demand from the user interface's **Harvest** page, which lists the sources and what they support,
starts incremental and full harvests, cancels them, follows their progress and shows their reports
and failed records. Viewing needs read and running harvests write access to `/harvested`, as the
platform's Web Access Control decides for the signed-in user (see
[harvest/README.md](harvest/README.md#the-harvest-api-and-the-user-interface)). Operators can also
use the `harvest` actuator endpoint on the service's management port (not exposed by the gateway).
The compose stack configures two demo sources that harvest the repository itself.

```bash
docker compose exec harvest curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"mode":"full"}' localhost:8000/actuator/harvest/self-oai
docker compose exec harvest curl -s localhost:8000/actuator/harvest/self-oai
```

## AI agent

The `ai` service ([ai/README.md](ai/README.md)) is an agent built on Spring AI. It
suggests a topology for some material (an existing one, or a draft opened in the topology editor),
metadata for images, videos and PDFs (read from the file and suggested by a model), the topics and a
summary of a community or collection, the overview, research areas and keywords of an academic
profile, and the collection and topology of harvested records. Each capability is an agent of the
[agent library](agent/README.md) while it is turned on, so the agent registry and
`/actuator/info` list exactly what runs. It
never writes: people review its suggestions and apply what they keep with the existing APIs; the
harvest service classifies harvested records with it and places them in collections at once
(`articulorium.harvest.classification.enabled` and `auto-accept`, both on in docker compose), or
leaves them in a review queue on the **Harvest** page (`ARTICULORIUM_HARVEST_AUTO_ACCEPT=false`). Asking about a resource needs write access to
it, and the agent reads only what the caller may read. The model is chosen with Spring AI's
settings: Ollama inside the deployment by default, or any OpenAI-compatible endpoint, with its key
outside source control. docker compose runs the agent, Ollama and its model
(several GB on first start, `ARTICULORIUM_AI_MODEL`). Without the agent the user interface shows no
AI action and harvested records are still ingested, their classifications recorded as failed.

```bash
docker compose up --build                       # includes the agent, Ollama and its model
curl -s http://localhost:8000/ai/capabilities
```

## Database

PostgreSQL is the default (`POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`,
`POSTGRES_PASSWORD`; docker compose runs it). Set `spring.datasource.url` to use H2, MySQL,
SQL Server or Oracle instead; their drivers are included. The OCFL object lock table, access
control lists (`ldp_acls`) and notifications (`ldn_notifications`) live in the same database;
Hibernate adds new tables and columns on start-up (`ddl-auto: update`).

## Storage (OCFL)

Content and versions are stored as [OCFL](https://ocfl.io/) objects through
[ocfl-java](https://github.com/OCFL/ocfl-java): one object per resource
(`info:articulorium/<path>`) holding its bytes (`content`), a snapshot of its description
(`description.nt`) and of a file's technical metadata (`technical.nt`), with every write an
immutable OCFL version. An archival group's object holds the group and all its members, each in its
own `members/<name>/` directory. Writes that are rolled back in
the database are rolled back in OCFL too. Set `articulorium.storage.type`
(env `ARTICULORIUM_STORAGE_TYPE`) to choose where the storage root lives:

| Value             | Settings (`articulorium.storage.…`)                                                     |
|-------------------|------------------------------------------------------------------------------------------|
| `s3` *(default)*  | `s3.endpoint`, `s3.region`, `s3.bucket`, `s3.access-key`, `s3.secret-key`, `s3.path-style`, `s3.create-bucket`, `s3.key-prefix` (default `ocfl`) |
| `filesystem`      | `filesystem.root`                                                                        |

`ocfl.work-directory` sets where versions are staged, `ocfl.database-lock` coordinates several
platform instances through the database. Limits: `articulorium.storage.max-upload-size` (raw
bodies) and `spring.servlet.multipart.*` (multipart uploads). With empty S3 keys the AWS default
credentials chain is used.

## Transformations

The transform service runs every active `Transformer` bean for each change and publishes the
merged document for the index. The built-in `field-mapping` transformer maps RDF property paths
to typed fields ([field-mappings.yml](transform/src/main/resources/transform/field-mappings.yml),
replacing the retired Apache Marmotta LDPath engine). Add a transformation by contributing a
Spring bean that implements `com.articulorium.transform.spi.Transformer` and listing its name in
`articulorium.transform.transformers`; `POST /transformers/<name>/preview?iri=…` tries one on a
posted RDF document. The transform service also serves the [IIIF](#iiif) exports.

## Internationalization

Articulorium is used in **12 languages**, shipped in every build:

| Tag     | Language              | Tag     | Language             |
|---------|-----------------------|---------|----------------------|
| `en`    | English (default)     | `ja`    | 日本語 (Japanese)     |
| `ar`    | العربية (Arabic, right to left) | `ko` | 한국어 (Korean) |
| `de`    | Deutsch (German)      | `nl`    | Nederlands (Dutch)   |
| `es`    | Español (Spanish)     | `pl`    | Polski (Polish)      |
| `fr`    | Français (French)     | `pt-BR` | Português (Brasil)   |
| `it`    | Italiano (Italian)    | `zh-CN` | 简体中文 (Simplified Chinese) |

The single list of shipped locales is the manifest
[`locales.properties`](i18n-spring-boot-starter/src/main/resources/articulorium/locales.properties)
of [i18n-spring-boot-starter](i18n-spring-boot-starter/README.md), the module every service uses
for locale support (settings, locale resolution, message bundles, locale propagation).

### How the locale is chosen

Every request gets one of the enabled locales, in this order:

1. the `lang` query parameter (`/ui/browse?lang=de`, `/platform/?lang=fr`), for links;
2. the `articulorium-locale` cookie (path `/`), which the user interface writes when the user
   picks a language in the toolbar's language menu;
3. `Accept-Language`, negotiated by weight: an exact tag wins (`pt-BR`), then a locale of the same
   language (`de-AT` gets `de`, `pt-PT` gets `pt-BR`, `zh` gets `zh-CN`);
4. the default locale, `articulorium.i18n.default-locale` (`en`).

### Where it applies

| Part | What follows the locale | Fallback |
|------|-------------------------|----------|
| User interface | every text, accessibility labels, validation and error messages, plural forms, dates and numbers (`Intl`), `<html lang dir>`, the paginator, right-to-left layout | missing texts show in the default locale (the service merges each translation over it) |
| `index.html` (service) | rendered by Thymeleaf in the locale with `lang`, `dir`, description and the client's texts inlined (`#ui-i18n`): the first frame is translated, no flash, no second request | `Content-Language` and `Vary: Accept-Language, Cookie` keep caches correct |
| Platform | HTML view of resources (texts, `lang`/`dir`, sizes, titles in the reader's language), error messages with a code (not found, conflicts, limits) | protocol diagnostics stay in the default locale; `Content-Language` says which, and the user interface then shows its own translated summary of the status first |
| Topology service | built-in topologies' names, descriptions and labels; problem titles | custom topologies keep their own labels; validation details are in the default locale |
| Transform service (IIIF) | labels Articulorium writes (`Rights`, `homepage`, `seeAlso`) in the request's locale; every language-tagged title, summary and metadata value keeps its language in IIIF language maps, so viewers pick the reader's | untagged values are `none`; metadata labels derived from property names are English |
| Search index | language-tagged texts also go to per-language fields (`title_txt_de`, `title_txt_ja`, Chinese and Korean `_txt_cjk`) with the matching analyzers of Solr or Elasticsearch, and `languages` lists them | languages without an analyzer stay in the language-neutral field only |
| Keycloak | sign-in sends `ui_locales`; the realm enables the shipped locales | Keycloak's own fallback (English) for locales it lacks |
| Services calling services | topology, transform and OAI pass the user's `Accept-Language` to the platform | calls outside a request (bus events) use the default locale |
| OAI-PMH | Dublin Core values keep their `xml:lang` | protocol errors are English: the OAI-PMH schema allows no language on them, and harvesters are programs |
| Swagger UI, actuator, logs | not localized (developer tools) | English |

Language-tagged literals (`"Moby Dick oder Der Wal"@de`) are shown in the reader's language
wherever the user interface picks a title or description, and marked with `lang` attributes.

### Configuring locales in a deployment

```yaml
articulorium:
  i18n:
    default-locale: en        # must be enabled; used for unsupported requests and missing texts
    locales: en, de, fr       # enabled locales in menu order; empty (default) = every shipped locale
    cookie-name: articulorium-locale
    parameter-name: lang
    verify-bundles: true      # log translations missing from a service's bundles at start-up
```

Set the same values in every service: the Consul key/value store shares them
(`config/application/articulorium/i18n/locales`), or use environment variables
(`ARTICULORIUM_I18N_LOCALES=en,de,fr`, `ARTICULORIUM_I18N_DEFAULT_LOCALE=de`). A locale that is
enabled but not shipped works with the default locale's texts. Enable the same locales in the
Keycloak realm (`supportedLocales`) for a translated login page.

### Contributing translations

Translations live next to the code that uses them, one file per locale:

| Texts | Default (English) | Translation |
|-------|-------------------|-------------|
| User interface | [`ui/src/i18n/en.json`](ui/src/i18n/en.json) | `ui/src/i18n/<tag>.json` |
| Shared server texts (HTTP statuses) | `i18n-spring-boot-starter/src/main/resources/i18n/common.properties` | `common_<tag with _>.properties` |
| A service's texts | `<module>/src/main/resources/i18n/messages.properties` | `messages_<tag with _>.properties` |

* Change a text: edit the English file and every translation; keep the keys and the
  placeholders (`{{name}}` in JSON, `{0}` in properties).
* Plural forms: a JSON group `{ "one": ..., "other": ... }` holds exactly the categories of the
  language's plural rules (`Intl.PluralRules`: Japanese only `other`, Polish `one`/`few`/`many`/
  `other`, Arabic six).
* Properties are UTF-8 `java.text.MessageFormat` patterns: write an apostrophe as `''` (or use the
  typographic `’`).
* Checks: `npm run i18n:check` in `ui` (run by the Maven build) and each module's `MessageBundles`
  test fail on a missing file, missing or unknown keys, wrong placeholders and plural forms.

### Adding a locale

1. Add a line to the manifest: `sv = Svenska` (append `, rtl` for right-to-left scripts).
2. Add `ui/src/i18n/sv.json`, `common_sv.properties` in the starter and `messages_sv.properties`
   in `service`, `platform`, `topology` and `transform` (copy the English files and translate).
3. Run `npm run i18n:check` (in `ui`) and `mvn verify`; add `sv` to the Keycloak realm's
   `supportedLocales`.

No other module changes: the locale menu, negotiation, document direction and formatting follow
the manifest.

## Event dispatch (Spring Integration)

Services that consume bus events (transform, index, triplestore) receive them through
[Spring Integration](https://spring.io/projects/spring-integration) message flows defined once in
the `event` module (`com.articulorium.event.integration`), built from Enterprise Integration
Patterns:

```
bus / publishEvent ─> channel adapter ─> message filter ─> resourceEventChannel ─┬─> ResourceEventHandler beans (in order)
                                                         └> documentEventChannel ─┴─> DocumentEventHandler beans (in order)
                                                                    handler failures ─> eventHandlerErrorChannel (dead letters)
```

* An `ApplicationEventListeningMessageProducer` per event type turns each `ResourceEvent` /
  `DocumentEvent` (Spring Cloud Bus delivers remote events as application events) into a message.
* A filter drops incomplete events (no resource / document id, or no action) with a warning.
* Each event type has a publish-subscribe channel (`EventChannels`). Every `ResourceEventHandler`
  and `DocumentEventHandler` bean is subscribed in `Ordered` order; further components can
  subscribe to the same channels by name.
* A failing handler is logged and its event sent as an `ErrorMessage` to
  `eventHandlerErrorChannel`; the other handlers still run and the failure never reaches the bus
  consumer. The channel has no subscribers by default: subscribe to it to park or retry events.
* Dispatch is synchronous on the bus consumer thread, as with plain application listeners.

Channels and endpoints report Micrometer metrics (`spring.integration.*` under
`/actuator/metrics`), and `/actuator/integrationgraph` on the management port of transform, index
and triplestore shows the flows.

## Agents

The agent library ([agent/](agent/README.md)) is plain Java; Spring Boot services add
[agent-spring-boot-starter](agent-spring-boot-starter/README.md), which auto-configures an
`AgentRegistry` from `Agent` beans, `AgentContributor` beans (components that act as an agent only
while they are turned on, such as the AI capabilities of the `ai` service, whose `/actuator/info`
lists `ai-topology-assistant`, `ai-media-describer`, `ai-topic-modeler`, `ai-profile-enhancer` and
`ai-harvest-classifier` when they are on) and `articulorium.agents.definitions`. The service declares its
agents in [application.yml](service/src/main/resources/application.yml) and serves them at
`/ui/api/agents` and `/ui/api/agents/<name>`; add one without a rebuild with, for example,
`ARTICULORIUM_AGENTS_DEFINITIONS_LARK_DESCRIPTION=sing` or a Consul key.

## Operations

Every service exposes Spring Boot Actuator on port 8000 (`/actuator/health` with details,
liveness and readiness probes, `info`, `metrics`, `loggers`). Health indicators cover each
dependency:

| Service       | Indicators                                                      |
|---------------|-----------------------------------------------------------------|
| platform      | `db` and `database` (connection, root container), `ocfl` (storage root), `minio` (bucket, S3 storage only), `kafka` |
| transform     | `kafka`                                                         |
| topology      | (none beyond the common ones)                                   |
| oai           | (none beyond the common ones); metric `oai.requests` by verb and outcome |
| index         | `solr` (ping, document count) or, with Elasticsearch as the search index, `elasticsearch` (document count); `kafka` |
| triplestore   | `qlever` (`/ping`, graph count; `blazegraph` or `jena` with those stores), `kafka` |
| all           | `consul`, `diskSpace`, `ping`                                   |

Disable one with `management.health.<name>.enabled=false`.

OpenAPI descriptions: platform `/.well-known/openapi` (Swagger UI `/.well-known/swagger-ui.html`,
outside the LDP namespace), transform `/v3/api-docs`, topology `/v3/api-docs`, oai `/v3/api-docs`,
service `/api/docs`; the gateway's `/swagger-ui.html` lists all five and is what the UI's **API**
link opens.

### SPARQL store (QLever, Blazegraph or Apache Jena)

The triplestore service keeps one named graph per resource, named by the resource's IRI, in a
SPARQL store. `articulorium.triplestore.backend` selects it; an unknown value stops the start-up.

| `backend`    | Store | Settings (`articulorium.triplestore.*`) |
|--------------|-------|------------------------------------------|
| `qlever` (default) | [QLever](https://github.com/ad-freiburg/qlever) | `service-url` (`http://qlever:7001`), `sparql-path`, `graph-store-path` (both the root), `access-token` (`QLEVER_ACCESS_TOKEN`, sent as `Authorization: Bearer`) |
| `blazegraph` | [Blazegraph](https://github.com/blazegraph/database) | `blazegraph.service-url` (`http://blazegraph:8080/bigdata`), `blazegraph.namespace` (`articulorium`), `blazegraph.username`, `blazegraph.password` |
| `jena`       | [Apache Jena Fuseki](https://jena.apache.org/documentation/fuseki2/) | `jena.service-url` (`http://fuseki:3030`), `jena.dataset` (`articulorium`), `jena.username`, `jena.password` |

Without the setting nothing changes: QLever, with the settings and requests it had before. The
settings can be given as environment variables (`ARTICULORIUM_TRIPLESTORE_BACKEND=jena`,
`ARTICULORIUM_TRIPLESTORE_JENA_SERVICE_URL=...`). `articulorium.triplestore.base-uri` (the base
graph names are minted under) applies to every backend.

* **QLever.** Graphs are replaced with the SPARQL 1.1 Graph Store HTTP Protocol. Compose runs the
  `adfreiburg/qlever` image with [triplestore/qlever/entrypoint.sh](triplestore/qlever/README.md),
  which builds an empty index on first start and persists updates.
* **Blazegraph.** `service-url` is the web application (`.../bigdata` in the Docker images,
  `http://localhost:9999/blazegraph` for `blazegraph.jar`). The service creates the namespace in
  quads mode when it does not exist; an existing namespace must be in quads mode (the default `kb`
  namespace is not, and the service logs an error for it). Blazegraph has no Graph Store Protocol:
  a graph is dropped with a SPARQL update and its new content posted, two requests that are not one
  transaction. Blazegraph is no longer maintained; the backend is for deployments that already run it.
  `docker compose -f docker-compose.yml -f docker-compose.blazegraph.yml up --build` runs the stack
  on it (workbench on <http://localhost:8889/bigdata/>).
* **Apache Jena Fuseki.** The dataset must exist and allow updates; its `sparql`, `update` and
  `data` services are used (`<service-url>/<dataset>/sparql`, ...), the names Fuseki gives them by
  default. `docker compose -f docker-compose.yml -f docker-compose.jena.yml up --build` runs the
  stack on it (<http://localhost:3030/>, admin password `FUSEKI_ADMIN_PASSWORD`).

A user name and password are sent with HTTP Basic authentication when set. The health component is
named after the backend (`qlever`, `blazegraph` or `jena`) and reports the SPARQL endpoint and the
number of graphs. Switching the backend does not copy graphs: a resource reaches the new store
when it is next modified.

## Building and testing

```bash
mvn verify            # all modules, including the Angular builds (ui, discovery-ui), lint and unit tests
mvn -Dskip.npm verify # skip the npm steps (reuses ui/dist when present)
```

The PostgreSQL integration test runs only when Docker is available.

End-to-end tests ([Cypress](https://www.cypress.io/), [e2e/](e2e/README.md)) drive the user
interface in a browser against the running stack, from the `e2e` compose service; GitHub Actions
runs them in the `compose` job:

```bash
docker compose up --build -d --wait
docker compose --profile e2e run --rm e2e # E2E_ARGS="--spec cypress/e2e/search.cy.ts" for one spec
```

## GraalVM native images

Every service has a `native` profile (Spring AOT + GraalVM Native Build Tools). Class path
resources need hints and some settings are fixed when an image is built: see
[GRAALVM_NATIVE_BUILDS.md](GRAALVM_NATIVE_BUILDS.md).

```bash
# with a local GraalVM 25
mvn -Pnative -pl platform -am package -DskipTests # -> platform/target/platform

# with Docker only
docker build -f Dockerfile.native --build-arg MODULE=gateway -t articulorium/gateway:native .

# the whole stack: build every image one at a time (each native build needs ~10 GB of memory;
# "up" would build missing images in parallel), then start it
docker compose -f docker-compose.yml -f docker-compose.native.yml build gateway
docker compose -f docker-compose.yml -f docker-compose.native.yml build platform
docker compose -f docker-compose.yml -f docker-compose.native.yml build transform
docker compose -f docker-compose.yml -f docker-compose.native.yml build index
docker compose -f docker-compose.yml -f docker-compose.native.yml build triplestore
docker compose -f docker-compose.yml -f docker-compose.native.yml build topology
docker compose -f docker-compose.yml -f docker-compose.native.yml build oai
docker compose -f docker-compose.yml -f docker-compose.native.yml build service
docker compose -f docker-compose.yml -f docker-compose.native.yml build harvest
docker compose -f docker-compose.yml -f docker-compose.native.yml build discovery
docker compose -f docker-compose.yml -f docker-compose.native.yml build ai
docker compose -f docker-compose.yml -f docker-compose.native.yml up
```

The gateway, index, triplestore, topology and oai are plain Spring Cloud services (the topology and
oai services and the transform service's IIIF export embed Jena like the platform). The platform also embeds
Apache Jena, which ships no official native metadata: `PlatformRuntimeHints` registers what Jena
loads reflectively, but if a native platform reports missing metadata at run time, record it once
with the tracing agent and commit the output:

```bash
java -agentlib:native-image-agent=config-output-dir=platform/src/main/resources/META-INF/native-image \
     -jar platform/target/platform-1.0.0-SNAPSHOT.jar   # then exercise the API
```
