![Java CI with Maven](https://github.com/william-stanley-welling/articulorium/workflows/maven_build/badge.svg)

# Articulorum

A W3C [Linked Data Platform 1.0](https://www.w3.org/TR/ldp/) server built as Spring Boot
microservices on [Spring Cloud Consul](https://spring.io/projects/spring-cloud-consul) and
Spring Cloud Bus (Kafka), with Fedora Commons compatible extensions (versions, fixity, search),
an [OCFL](https://ocfl.io/) storage layer on the local file system or any S3 compatible object
store (MinIO by default), PostgreSQL for resource metadata, and an Angular user interface at
feature parity with the Fedora Commons web UI.

Built on Java 25, Spring Boot 4.1 and Spring Cloud 2025.1.

| Module        | Role                                                                                   |
|---------------|----------------------------------------------------------------------------------------|
| `domain`      | LDP resource model, vocabularies, IRI and N-Triples helpers (no framework dependencies) |
| `event`       | Spring Cloud Bus events (`ResourceEvent`, `DocumentEvent`) and listener plumbing        |
| `agent`       | Agent model shared with the user interface                                              |
| `ui`          | Angular single page application, packaged as static resources ([ui/README.md](ui/README.md)) |
| `platform`    | The LDP server: RDF sources, containers, files, versions, fixity, search, OCFL storage  |
| `gateway`     | Spring Cloud Gateway; public entry point (`/ui/**`, `/platform/**`, `/transform/**`) and one Swagger UI for all services |
| `service`     | Serves the UI and its support API (settings, agents, media type detection with Apache Tika) |
| `transform`   | Turns resources into search documents with pluggable transformers                       |
| `index`       | Keeps a Solr collection in sync                                                         |
| `triplestore` | Mirrors every resource into a SPARQL 1.1 store (Blazegraph), one named graph each       |

```
browser ──> gateway ──> service (UI)
               └─────> platform ──(resource-bus)──> transform ──(document-bus)──> index ──> Solr
                          │   └──────────────(resource-bus)──> triplestore ──> Blazegraph
                          └──> PostgreSQL (metadata)   OCFL on MinIO/S3 or disk (content, versions)
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
repository, create containers, upload files, edit properties with SPARQL Update, and create and
view versions. <http://localhost:8000/platform/> is the LDP root itself, serving Turtle (default)
or any other RDF syntax to RDF clients and a simple HTML view to browsers.

| URL                                  | What                                   |
|--------------------------------------|----------------------------------------|
| http://localhost:8000/ui/            | User interface                         |
| http://localhost:8000/platform/      | LDP root through the gateway           |
| http://localhost:8000/swagger-ui.html | Swagger UI for the platform, transform and service APIs |
| http://localhost:9001/               | LDP root directly on the platform      |
| localhost:5432                       | PostgreSQL (`articulorum` / `articulorum`) |
| http://localhost:8500/            | Consul UI                              |
| http://localhost:9101/            | MinIO console (`articulorum` / `articulorum-secret`) |
| http://localhost:8983/            | Solr admin                             |
| http://localhost:8080/bigdata/    | Blazegraph workbench                   |

`docker compose --profile tools up` also starts a Kafka UI on port 9091.

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
| `GET /fcr:search?condition=…`         | Search on `fedora_id`, `created`, `modified`, `content_size`, `mime_type`, `rdf_type` with `=`, `<`, `<=`, `>`, `>=` and `*` wildcards; `max_results`, `offset`, `order_by`, `order` |

Every file write is a new version automatically. The 25 namespace prefixes Fedora Commons
declares by default (plus PCDM, ORE, SKOS, schema.org and others) are pre-registered
([namespaces.yml](platform/src/main/resources/namespaces.yml)) and used in every RDF response.

## Database

PostgreSQL is the default (`POSTGRES_HOST`, `POSTGRES_PORT`, `POSTGRES_DB`, `POSTGRES_USER`,
`POSTGRES_PASSWORD`; docker compose runs it). Set `spring.datasource.url` to use H2, MySQL,
SQL Server or Oracle instead; their drivers are included. The OCFL object lock table lives in the
same database (PostgreSQL, MariaDB and H2).

## Storage (OCFL)

Content and versions are stored as [OCFL](https://ocfl.io/) objects through
[ocfl-java](https://github.com/OCFL/ocfl-java): one object per resource
(`info:articulorum/<path>`) holding its bytes (`content`) and a snapshot of its description
(`description.nt`), with every write an immutable OCFL version. Writes that are rolled back in
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
posted RDF document.

## Operations

Every service exposes Spring Boot Actuator on port 8000 (`/actuator/health` with details,
liveness and readiness probes, `info`, `metrics`, `loggers`). Health indicators cover each
dependency:

| Service       | Indicators                                                      |
|---------------|-----------------------------------------------------------------|
| platform      | `db` and `database` (connection, root container), `ocfl` (storage root), `minio` (bucket, S3 storage only), `kafka` |
| transform     | `kafka`                                                         |
| index         | `solr` (ping, document count), `kafka`                          |
| triplestore   | `blazegraph` (SPARQL ASK, graph count), `kafka`                 |
| all           | `consul`, `diskSpace`, `ping`                                   |

Disable one with `management.health.<name>.enabled=false`.

OpenAPI descriptions: platform `/.well-known/openapi` (Swagger UI `/.well-known/swagger-ui.html`,
outside the LDP namespace), transform `/v3/api-docs`, service `/api/docs`; the gateway's
`/swagger-ui.html` lists all three.

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
docker compose -f docker-compose.yml -f docker-compose.native.yml build service
docker compose -f docker-compose.yml -f docker-compose.native.yml up
```

The gateway, index and triplestore are plain Spring Cloud services. The platform also embeds
Apache Jena, which ships no official native metadata: `PlatformRuntimeHints` registers what Jena
loads reflectively, but if a native platform reports missing metadata at run time, record it once
with the tracing agent and commit the output:

```bash
java -agentlib:native-image-agent=config-output-dir=platform/src/main/resources/META-INF/native-image \
     -jar platform/target/platform-1.0.0-SNAPSHOT.jar   # then exercise the API
```
