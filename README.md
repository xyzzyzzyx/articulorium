![Java CI with Maven](https://github.com/xyzzyzzyx/articulorium/workflows/maven_build/badge.svg)

# Articulorum

A W3C [Linked Data Platform 1.0](https://www.w3.org/TR/ldp/) server built as Spring Boot
microservices on [Spring Cloud Consul](https://spring.io/projects/spring-cloud-consul) and
Spring Cloud Bus (Kafka), with Fedora Commons compatible extensions (versions, fixity, search,
archival groups, Web Access Control), an [OCFL](https://ocfl.io/) storage layer on the local file
system or any S3 compatible object store (MinIO by default), PostgreSQL for resource metadata, and
an Angular user interface at feature parity with the Fedora Commons web UI.

On top of plain LDP, Articulorum offers digital library structures: communities, sub-communities
and collections; PCDM *topologies* (Book, Newspaper, Journal, Postcards, ... and custom ones) that
build and navigate the resources of a digital object; IIIF Presentation 3.0 export of those
structures; and [COAR Notify](https://coar-notify.net/) inboxes for every container.

Built on Java 25, Spring Boot 4.1 and Spring Cloud 2025.1.

| Module        | Role                                                                                   |
|---------------|----------------------------------------------------------------------------------------|
| `domain`      | LDP resource model, vocabularies, IRI and N-Triples helpers (no framework dependencies) |
| `event`       | Spring Cloud Bus events (`ResourceEvent`, `DocumentEvent`) and listener plumbing        |
| `agent`       | Agent model shared with the user interface                                              |
| `ui`          | Angular single page application, packaged as static resources ([ui/README.md](ui/README.md)) |
| `platform`    | The LDP server: RDF sources, containers, files, versions, fixity, search, OCFL storage  |
| `gateway`     | Spring Cloud Gateway; public entry point (`/ui/**`, `/platform/**`, `/transform/**`, `/topology/**`) and one Swagger UI for all services |
| `service`     | Serves the UI and its support API (settings, agents, media type detection with Apache Tika) |
| `transform`   | Turns resources into search documents with pluggable transformers; exports PCDM structures as IIIF ([transform/README.md](transform/README.md)) |
| `topology`    | PCDM topologies and building their resources in the platform ([topology/README.md](topology/README.md)) |
| `index`       | Keeps a Solr collection in sync                                                         |
| `triplestore` | Mirrors every resource into a SPARQL 1.1 store ([QLever](https://github.com/ad-freiburg/qlever)), one named graph each |

```
browser ──> gateway ──> service (UI)
               ├─────> topology ───────────────(LDP)──┐
               ├─────> transform (IIIF) ───────(LDP)──┤
               └─────> platform <─────────────────────┘
                          │   └──(resource-bus)──> transform ──(document-bus)──> index ──> Solr
                          │   └──(resource-bus)──> triplestore ──> QLever
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
topologies, read and send notifications and open IIIF manifests.
<http://localhost:8000/platform/> is the LDP root itself, serving Turtle (default) or any other
RDF syntax to RDF clients and a simple HTML view to browsers.

| URL                                  | What                                   |
|--------------------------------------|----------------------------------------|
| http://localhost:8000/ui/            | User interface                         |
| http://localhost:8000/platform/      | LDP root through the gateway           |
| http://localhost:8000/swagger-ui.html | Swagger UI for the platform, transform, topology and service APIs (the UI's "API" link) |
| http://localhost:8000/topology/topologies | PCDM topologies                   |
| http://localhost:8000/transform/iiif/communities | IIIF Collection of the community structure |
| http://localhost:9001/               | LDP root directly on the platform      |
| localhost:5432                       | PostgreSQL (`articulorum` / `articulorum`) |
| http://localhost:8500/            | Consul UI                              |
| http://localhost:9101/            | MinIO console (`articulorum` / `articulorum-secret`) |
| http://localhost:8983/            | Solr admin                             |
| http://localhost:7001/            | QLever SPARQL endpoint (updates need `QLEVER_ACCESS_TOKEN`) |

`docker compose --profile tools up` also starts a Kafka UI on port 9091.
`docker compose -f docker-compose.yml -f docker-compose.acl.yml up` turns on Consul ACLs and
accepts Consul tokens as identities (see [Access control](#access-control-webac)).

### Running the platform alone

```bash
mvn -pl platform -am package -DskipTests
java -jar platform/target/platform-1.0.0-SNAPSHOT.jar \
  --spring.cloud.consul.enabled=false --spring.cloud.bus.enabled=false \
  --articulorum.storage.type=filesystem \
  --spring.datasource.url=jdbc:h2:file:./data/db/articulorum   # or a PostgreSQL URL
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
| `GET/POST <container>/fcr:inbox`      | The container's COAR Notify inbox; `GET <container>/fcr:inbox/<id>` one notification |
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
  start; members link to it with `Link: <group>; rel="https://articulorum.org/ns/repository#archivalGroup"`.
* Inside the object every member keeps its files in its own directory (`members/<name>/…`).
  Every file write inside the group is a version of the group; `POST <group>/fcr:versions`
  snapshots the descriptions of the group and every member.
* `GET <member>/fcr:versions` lists the group versions that contain the member; members cannot be
  versioned on their own (`POST` answers 405). Deleting a member writes a group version without
  its files; deleting the group purges the object.
* Archival groups cannot be nested, and only containers can be archival groups (409 otherwise).

## Access control (WebAC)

Every request to the platform is checked against [Web Access Control](https://solidproject.org/TR/wac)
authorizations (`articulorum.webac.enabled`, default `true`).

* **Effective ACL**: the resource's own ACL (`<r>/fcr:acl`, Turtle or any RDF syntax), else the
  nearest ancestor's (only its `acl:default` authorizations apply), else the root ACL configured
  with `articulorum.webac.root-acl` ([default](platform/src/main/resources/webac/root-acl.ttl):
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

**Agents.** With `articulorum.webac.consul.enabled=true` the platform accepts **Consul ACL tokens**
(`Authorization: Bearer <secret id>` or `X-Consul-Token`) and validates them with Consul
(`/v1/acl/token/self`, cached for `cache-ttl`). The token's description (or accessor ID) becomes the
agent `info:consul/agent/<description>`; its roles and policies become the groups
`info:consul/role/<name>` and `info:consul/policy/<name>`. Alternatively
`articulorum.webac.trusted-agent-header` names a header set by a trusted authenticating proxy, and
a principal authenticated by the servlet container is used as is.

`docker-compose.acl.yml` sets this up: Consul runs with ACLs, the platform accepts tokens and uses
[root-acl-consul.ttl](platform/src/main/resources/webac/root-acl-consul.ttl) (everyone reads, role
`editors` writes, role `administrators` also controls ACLs). Create a role and a token, then paste
the token's `SecretID` into the UI's **Sign in** dialog:

```bash
export CONSUL_HTTP_TOKEN=articulorum-consul-management     # CONSUL_MANAGEMENT_TOKEN
docker compose exec -e CONSUL_HTTP_TOKEN consul consul acl policy create -name articulorum-read \
  -rules 'key_prefix "" { policy = "read" }'
docker compose exec -e CONSUL_HTTP_TOKEN consul consul acl role create -name editors -policy-name articulorum-read
docker compose exec -e CONSUL_HTTP_TOKEN consul consul acl token create -description "Jane Doe" -role-name editors
```

## Communities, sub-communities and collections

The platform creates `/communities` (`articulorum.ldp.communities-path`, typed
`art:CommunityRoot`) on start-up. Below it, communities are containers typed `art:Community`
(a community inside a community is a sub-community) and collections containers typed
`art:Collection` and `pcdm:Collection` (`art:` is `https://articulorum.org/ns/repository#`).
Everything is plain LDP, so it can be created with any LDP client; the UI's **Communities** page
navigates the structure, creates communities, sub-communities and collections, and builds items
in collections from topologies.

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

## Notifications (COAR Notify)

Every container (community, collection, ...) has a [Linked Data Notifications](https://www.w3.org/TR/ldn/)
inbox advertised with `Link: <container/fcr:inbox>; rel="http://www.w3.org/ns/ldp#inbox"`. POSTing
a COAR Notify notification (`application/ld+json`; an Activity Streams activity such as `Offer` or
`Announce` with a COAR Notify type such as `coar-notify:ReviewAction`, `origin`, `target` and
`object`) stores it after validation (400 otherwise); GET lists the inbox (JSON summaries for
`application/json`, an `ldp:contains` listing for RDF). `POST <container>/fcr:outbox` sends a
notification on behalf of the container, filling in `id`, contexts and `origin`, and records
whether the target inbox accepted it. The UI's **Notifications** page of a container shows both and
sends notifications for the common patterns (request review or endorsement, announce review,
endorsement, relationship or ingest, accept, reject, undo).

## Database

PostgreSQL is the default (`POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`,
`POSTGRES_PASSWORD`; docker compose runs it). Set `spring.datasource.url` to use H2, MySQL,
SQL Server or Oracle instead; their drivers are included. The OCFL object lock table, access
control lists (`ldp_acls`) and notifications (`ldn_notifications`) live in the same database;
Hibernate adds new tables and columns on start-up (`ddl-auto: update`).

## Storage (OCFL)

Content and versions are stored as [OCFL](https://ocfl.io/) objects through
[ocfl-java](https://github.com/OCFL/ocfl-java): one object per resource
(`info:articulorum/<path>`) holding its bytes (`content`), a snapshot of its description
(`description.nt`) and of a file's technical metadata (`technical.nt`), with every write an
immutable OCFL version. An archival group's object holds the group and all its members, each in its
own `members/<name>/` directory. Writes that are rolled back in
the database are rolled back in OCFL too. Set `articulorum.storage.type`
(env `ARTICULORUM_STORAGE_TYPE`) to choose where the storage root lives:

| Value             | Settings (`articulorum.storage.…`)                                                     |
|-------------------|------------------------------------------------------------------------------------------|
| `s3` *(default)*  | `s3.endpoint`, `s3.region`, `s3.bucket`, `s3.access-key`, `s3.secret-key`, `s3.path-style`, `s3.create-bucket`, `s3.key-prefix` (default `ocfl`) |
| `filesystem`      | `filesystem.root`                                                                        |

`ocfl.work-directory` sets where versions are staged, `ocfl.database-lock` coordinates several
platform instances through the database. Limits: `articulorum.storage.max-upload-size` (raw
bodies) and `spring.servlet.multipart.*` (multipart uploads). With empty S3 keys the AWS default
credentials chain is used.

## Transformations

The transform service runs every active `Transformer` bean for each change and publishes the
merged document for the index. The built-in `field-mapping` transformer maps RDF property paths
to typed fields ([field-mappings.yml](transform/src/main/resources/transform/field-mappings.yml),
replacing the retired Apache Marmotta LDPath engine). Add a transformation by contributing a
Spring bean that implements `com.articulorum.transform.spi.Transformer` and listing its name in
`articulorum.transform.transformers`; `POST /transformers/<name>/preview?iri=…` tries one on a
posted RDF document. The transform service also serves the [IIIF](#iiif) exports.

## Operations

Every service exposes Spring Boot Actuator on port 8000 (`/actuator/health` with details,
liveness and readiness probes, `info`, `metrics`, `loggers`). Health indicators cover each
dependency:

| Service       | Indicators                                                      |
|---------------|-----------------------------------------------------------------|
| platform      | `db` and `database` (connection, root container), `ocfl` (storage root), `minio` (bucket, S3 storage only), `kafka` |
| transform     | `kafka`                                                         |
| topology      | (none beyond the common ones)                                   |
| index         | `solr` (ping, document count), `kafka`                          |
| triplestore   | `qlever` (`/ping`, graph count), `kafka`                        |
| all           | `consul`, `diskSpace`, `ping`                                   |

Disable one with `management.health.<name>.enabled=false`.

OpenAPI descriptions: platform `/.well-known/openapi` (Swagger UI `/.well-known/swagger-ui.html`,
outside the LDP namespace), transform `/v3/api-docs`, topology `/v3/api-docs`, service
`/api/docs`; the gateway's `/swagger-ui.html` lists all four and is what the UI's **API** link
opens.

### SPARQL store (QLever)

The triplestore service keeps one named graph per resource in [QLever](https://github.com/ad-freiburg/qlever)
(Blazegraph is no longer maintained), replacing graphs with the SPARQL 1.1 Graph Store HTTP
Protocol and authenticating with QLever's access token (`QLEVER_ACCESS_TOKEN`, sent as
`Authorization: Bearer`). Compose runs the `adfreiburg/qlever` image with
[triplestore/qlever/entrypoint.sh](triplestore/qlever/README.md), which builds an empty index on
first start and persists updates. Any store implementing the same protocols works:
`articulorum.triplestore.service-url`, `sparql-path`, `graph-store-path`, `access-token`.

## Building and testing

```bash
mvn verify            # all modules, including the Angular build, lint and unit tests
mvn -Dskip.npm verify # skip the npm steps (reuses ui/dist when present)
```

The PostgreSQL integration test runs only when Docker is available.

End-to-end tests ([Cypress](https://www.cypress.io/), [e2e/](e2e/README.md)) drive the user
interface in a browser against the running stack, from the `e2e` compose service; GitHub Actions
runs them in the `compose` job:

```bash
docker compose up --build -d --wait
docker compose --profile e2e run --rm e2e      # E2E_ARGS="--spec cypress/e2e/search.cy.ts" for one spec
```

## GraalVM native images

Every service has a `native` profile (Spring AOT + GraalVM Native Build Tools).

```bash
# with a local GraalVM 25
mvn -Pnative -pl platform -am package -DskipTests      # -> platform/target/platform

# with Docker only
docker build -f Dockerfile.native --build-arg MODULE=gateway -t articulorum/gateway:native .

# the whole stack: build the images one at a time (each needs ~10 GB), then start it
docker compose -f docker-compose.yml -f docker-compose.native.yml build gateway
docker compose -f docker-compose.yml -f docker-compose.native.yml build platform
docker compose -f docker-compose.yml -f docker-compose.native.yml build transform 
docker compose -f docker-compose.yml -f docker-compose.native.yml build index
docker compose -f docker-compose.yml -f docker-compose.native.yml build triplestore
docker compose -f docker-compose.yml -f docker-compose.native.yml build topology
docker compose -f docker-compose.yml -f docker-compose.native.yml build service
docker compose -f docker-compose.yml -f docker-compose.native.yml up
```

The gateway, index, triplestore and topology are plain Spring Cloud services (the topology service
and the transform service's IIIF export embed Jena like the platform). The platform also embeds
Apache Jena, which ships no official native metadata: `PlatformRuntimeHints` registers what Jena
loads reflectively, but if a native platform reports missing metadata at run time, record it once
with the tracing agent and commit the output:

```bash
java -agentlib:native-image-agent=config-output-dir=platform/src/main/resources/META-INF/native-image \
     -jar platform/target/platform-1.0.0-SNAPSHOT.jar   # then exercise the API
```
