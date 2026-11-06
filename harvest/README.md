# Articulorium harvest service

Harvests records from external repositories and ingests them into the platform: Fedora Commons
(and other Articulorium instances), DSpace, VIVO and any OAI-PMH 2.0 data provider. Every source
is configured with an identifier, a type and an endpoint; its records are mapped to one shared
representation and stored as resources below `/harvested/<source>/`, replaced on every harvest.

Harvests run on a schedule per source (incremental) or on demand through the `harvest` actuator
endpoint. The service has no public API: the endpoint is on the management port, which the gateway
does not expose.

```bash
# in the compose stack (the demo sources harvest this repository itself)
docker compose exec harvest curl -s localhost:8000/actuator/harvest
docker compose exec harvest curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"mode":"full"}' localhost:8000/actuator/harvest/self-oai
docker compose exec harvest curl -s localhost:8000/actuator/harvest/self-oai   # progress and reports
curl -H 'Accept: text/turtle' http://localhost:8000/platform/harvested/self-oai
```

## Design

```
                         ┌────────────── core (plain Java) ───────────────┐
 HarvestSourceFactory ──>│ HarvestSource ──pages──> Harvester ──records──>│ RecordIngestor ──> platform (LDP)
 (oai-pmh, dspace,       │  fetch(window, cursor)   paging, retries,      │  ingest(source, record)
  fedora, vivo)          │                          checkpoints, reports  │ CheckpointStore ──> platform
                         └────────────────────────────────────────────────┘
```

* **`com.articulorium.harvest.core`** holds the abstractions and the workflow, with no Spring and
  no source in it (a test enforces that): `HarvestSource` (pages of `HarvestedRecord`s behind an
  opaque cursor), `RecordIngestor`, `CheckpointStore`, `HarvestSourceFactory` and the `Harvester`.
  They can be used without the service, for example with an `InMemoryCheckpointStore`:

  ```java
  Harvester harvester = new Harvester(HarvestPolicy.DEFAULT);
  HarvestReport report = harvester.run(source, ingestor, new InMemoryCheckpointStore(), HarvestMode.FULL);
  ```

* **`HarvestedRecord`**, the shared ingest representation: the record's identifier in its source,
  the IRI standing for it (`subject`), its datestamp, whether it was deleted, its sets, its
  metadata as an RDF graph about `subject`, and its files (referenced where the source keeps them).
* **Sources** (`com.articulorium.harvest.source.*`) map a protocol to that representation.
* **Ingest** (`com.articulorium.harvest.ingest`) stores records in the platform with the
  harvester's own credentials and keeps each source's checkpoint on its container.
* **Service** (`HarvestService`, `HarvestEndpoint`) builds the configured sources at startup (a
  misconfigured source fails the startup with its identifier and the reason), runs them on their
  schedules and on demand, never two harvests of one source at once, and keeps the latest reports.

## Sources

| Type      | Protocol                                     | Paging                                  | Incremental                          | Deletions                     |
|-----------|----------------------------------------------|-----------------------------------------|--------------------------------------|-------------------------------|
| `oai-pmh` | OAI-PMH 2.0 `ListRecords`                    | resumption tokens                       | `from` at the provider's granularity; the checkpoint is the provider's `responseDate` | `status="deleted"` records |
| `dspace`  | DSpace 7+ REST API, discovery search         | keyset on `lastModified`                | `lastModified` range query           | withdrawn items only          |
| `fedora`  | Fedora 6 search (`fcr:search`) and LDP; also Articulorium | keyset on `modified`         | `modified>=` condition               | resources gone when read only |
| `vivo`    | VIVO SPARQL Query API (`api/sparqlQuery`)    | keyset on the individual's IRI          | no (VIVO keeps no modification times) | individuals left without triples |

**Keyset paging.** Offset paging skips records when the listing changes during a harvest: a record
modified after it was read moves to the end and every record behind it moves one place forward.
The `dspace` and `fedora` sources page on the modification time instead: each page lists the
records modified at or after the last one read and skips those already read at that very time
(`KeysetCursor`). The `vivo` source pages on the last IRI read.

### `oai-pmh`

`url` is the provider's base URL. Credentials, when set, are sent as `Authorization`.

| Option            | Default  | Meaning                                                                 |
|-------------------|----------|-------------------------------------------------------------------------|
| `metadata-prefix` | `oai_dc` | the format to harvest; needs an `OaiMetadataMapper` (only `oai_dc` is shipped) |
| `set`             |          | harvest one set                                                         |
| `granularity`     |          | `day` or `second`; read with `Identify` when unset                     |

The record's subject is its OAI identifier (OAI-PMH identifiers are URIs). `oai_dc` elements map to
the `dcterms:` property of the same name, keeping `xml:lang`. A record whose metadata cannot be
mapped is reported as a failure. A `badResumptionToken` error fails the harvest; the next one
starts again from the checkpoint.

### `dspace`

`url` is the REST API root, e.g. `https://demo.dspace.org/server/api`. A `token` is sent as
`Authorization: Bearer` (a DSpace JWT); without one only public, discoverable items are harvested.

| Option              | Default                       | Meaning                                                      |
|---------------------|-------------------------------|--------------------------------------------------------------|
| `scope`             |                               | UUID of a community or collection to harvest; it becomes the records' set |
| `include-files`     | `false`                       | reference the bitstreams of `bundle` (one request per item)  |
| `bundle`            | `ORIGINAL`                    | the bundle whose bitstreams are referenced                   |
| `sort-field`        | `lastModified`                | the discovery sort option on the modification time          |
| `incremental-query` | `lastModified:[{from} TO *]`  | the discovery query of keyset pages; `{from}` is replaced    |
| `handle-resolver`   | `https://hdl.handle.net/`     | prefix turning handles into IRIs                             |

The subject is the item's handle URL (its REST URL without a handle). `dc.*` and `dcterms.*`
fields map to `dcterms:`, qualified fields to their DCMI refinement where one exists
(`dc.date.issued` → `dcterms:issued`, `dc.contributor.author` → `dcterms:creator`,
`dc.rights.uri` → `dcterms:license` as an IRI) and to the element otherwise; `en_US` languages
become `en-US`; other schemas are left out. The harvest fails when the listing is not ordered by
modification time (a discovery configuration without the `lastModified` sort): set `sort-field`.

### `fedora`

`url` is the LDP base, e.g. `http://localhost:8080/rest/`. Credentials are sent as HTTP Basic
(`username`/`password`, such as `fedoraAdmin`) or bearer token.

| Option             | Default             | Meaning                                                          |
|--------------------|---------------------|------------------------------------------------------------------|
| `root`             | the whole repository | container below the base to harvest (its descendants)          |
| `rdf-type`         |                     | only resources of this type, e.g. `http://pcdm.org/models#Object` |
| `include-binaries` | `true`              | binaries become records whose subject is their description, the binary referenced as a file |

Each RDF source becomes a record with its RDF, read with `Prefer: omit` of server-managed triples
and containment. Records are identified by their path below the base. Hash IRIs and links to other
resources keep their original IRIs. A resource the search lists but that is gone when read becomes
a deleted record.

### `vivo`

`url` is the VIVO base, e.g. `https://vivo.example.edu/vivo/`. With `username` (the account's
e-mail) and `password` they are sent as the API's `email` and `password` parameters (the account
needs the SPARQL query permission; by default only root has it). A `token` is sent as a bearer
token (an endpoint behind a proxy).

| Option     | Default                                    | Meaning                                                  |
|------------|--------------------------------------------|----------------------------------------------------------|
| `classes`  | `http://xmlns.com/foaf/0.1/Person`         | comma-separated classes of the individuals to harvest (full IRIs) |
| `endpoint` | `api/sparqlQuery`                          | the query API, relative to the base or absolute          |
| `context`  | `vivo:relatedBy`, `obo:ARG_2000028`, `obo:RO_0000053`, `obo:RO_0000056`, `vivo:hasResearchArea` | predicates leading to an individual's context (full IRIs) |

Every individual becomes a record with its own triples, the triples of the individuals it reaches
through the context predicates (positions, authorships, vCards, educational processes, research
areas) and of what those link to (organizations, documents, date-time intervals and their values),
so a profile arrives with its positions and publications. Every VIVO harvest is a full harvest.

## What is stored

Every record becomes a basic container at `<container>/<source>/<slug>`. The slug is the end of
the identifier in lower-case letters, digits and hyphens followed by 12 hex digits of its SHA-256
(`oai:arXiv.org:2401.00001` → `oai-arxiv-org-2401-00001-1b2c3d4e5f6a`), so harvesting a record again
replaces its copy (`PUT`), and the copy of a deleted record is deleted.

* The record's metadata, with its `subject` renamed to the copy's IRI; `ldp:` and `fedora:`
  statements are dropped (the platform manages them).
* `rdfs:label` from the titles when the record has no label (the user interface and search show
  labels).
* Provenance: `a art:HarvestedRecord`; `art:harvestSource`, `art:harvestIdentifier`,
  `art:harvestDatestamp`, `art:harvestedAt`, `art:harvestSet`; `prov:wasDerivedFrom` the original
  (http(s) subjects); `art:harvestFile` for each file, described with `ebucore:filename`,
  `ebucore:hasMimeType` and `premis:hasSize`.

`/harvested` is typed `art:HarvestRoot`; each source's container is typed `art:HarvestSource` and
carries the source's checkpoint (`art:harvestCheckpoint`). The containers are created once with
`If-None-Match: *` and never replaced. Deleting a source's container starts it afresh.

## Failures, checkpoints and reports

* **Recoverable failures** (network errors, timeouts, 408, 429, 5xx, a refused platform token) are
  retried `max-attempts` times with exponential backoff from `backoff` up to `max-backoff`,
  honouring `Retry-After`. When a page still cannot be fetched or the platform still refuses, the
  harvest stops as `FAILED`.
* **Record failures** (unmappable metadata, a resource the source forbids, a copy the platform
  refuses) are counted and listed in the report (the first `max-reported-failures`), and the
  harvest goes on; after `max-failures` it stops as `FAILED`.
* **No record is lost silently**: the checkpoint moves only after a harvest that read the source
  to the end without a single failure (`COMPLETED`). After `COMPLETED_WITH_ERRORS`, `FAILED` or
  `CANCELLED` the next incremental harvest starts from the same checkpoint and reads the failed
  records again.
* The checkpoint is the source's time when it answered the first page (OAI-PMH `responseDate`) or
  the harvest's start, minus `overlap`: records changed while harvesting and small clock
  differences are covered by reading a little twice.
* Reports hold the outcome, the window (`from`), the saved checkpoint, the number of pages and
  records, what ingest did (`created`, `updated`, `deleted`, `skipped`, `failed`), the failures
  and a message. The service keeps the latest `history` reports per source, in memory.

Metrics: `harvest.runs{source,outcome}`, `harvest.duration{source,outcome}`,
`harvest.records{source,action}` (`CREATED`, `UPDATED`, `DELETED`, `SKIPPED`, `FAILED`).

## The `harvest` actuator endpoint

| Request                                   | Effect                                                         |
|-------------------------------------------|----------------------------------------------------------------|
| `GET /actuator/harvest`                   | every source: type, url, schedule, capabilities, running harvest, latest reports |
| `GET /actuator/harvest/{source}`          | one source (404 when unknown)                                  |
| `POST /actuator/harvest/{source}` `{"mode": "full"}` | starts a harvest (202); `mode` is `incremental` (default) or `full`; 409 when the source is disabled or already being harvested |
| `DELETE /actuator/harvest/{source}`       | stops the running harvest after the record in progress (202; 409 when none) |

Actuator write operations need `Content-Type: application/json`, even with an empty body (`{}`).

## Configuration (`articulorium.harvest.*`)

| Property                         | Default                        | Meaning                                                       |
|----------------------------------|--------------------------------|---------------------------------------------------------------|
| `platform-url`                   | `http://articulorium-platform/` | where the service reaches the platform                       |
| `load-balanced`                  | `true`                         | whether `platform-url` names a Consul service                 |
| `container`                      | `harvested`                    | container holding a container per source                      |
| `platform-auth.authorization`    | `HARVEST_PLATFORM_AUTHORIZATION` | a fixed `Authorization` header towards the platform (`Bearer <Consul token>`) |
| `platform-auth.token-uri`, `client-id`, `client-secret`, `scope` | | an OAuth 2.0 client credentials grant instead (a Keycloak confidential client with a service account); tokens are cached and renewed |
| `policy.max-attempts`            | `3`                            | attempts of one page fetch or record ingest                   |
| `policy.backoff`, `policy.max-backoff` | `2s`, `1m`               | first and longest delay between retries                       |
| `policy.max-failures`            | `100`                          | failed records after which a harvest stops (0: no limit)      |
| `policy.max-reported-failures`   | `100`                          | failed records listed in a report                             |
| `policy.overlap`                 | `5m`                           | how far before a harvest the next incremental one starts      |
| `http.connect-timeout`, `http.read-timeout`, `http.user-agent` | `10s`, `2m`, `Articulorium-Harvester/1.0 (…)` | requests to the sources |
| `history`                        | `10`                           | reports kept per source                                       |
| `concurrency`                    | `2`                            | harvests running at once (others wait)                        |
| `sources.<id>.type`              |                                | `oai-pmh`, `dspace`, `fedora` or `vivo`                       |
| `sources.<id>.url`               |                                | the endpoint (no credentials in it)                           |
| `sources.<id>.enabled`           | `true`                         | whether it may be harvested                                   |
| `sources.<id>.schedule`          |                                | cron expression (seconds first) of incremental harvests; on demand only when empty |
| `sources.<id>.page-size`         | `100`                          | records asked for per page where the protocol lets the client choose |
| `sources.<id>.username`, `password`, `token` |                    | credentials (see each type)                                   |
| `sources.<id>.options.*`         |                                | type options (above); unknown options fail the startup       |

```yaml
articulorium:
  harvest:
    sources:
      arxiv-cs:
        type: oai-pmh
        url: https://oaipmh.arxiv.org/oai
        schedule: "0 30 2 * * *"
        options:
          set: cs
      demo-dspace:
        type: dspace
        url: https://demo.dspace.org/server/api
        page-size: 50
        options:
          include-files: "true"
      fedora:
        type: fedora
        url: http://fedora:8080/rest/
        username: fedoraAdmin
        password: ${FEDORA_PASSWORD}
        options:
          root: collections
          rdf-type: http://pcdm.org/models#Object
      vivo:
        type: vivo
        url: https://vivo.example.edu/vivo/
        username: vivo_root@example.edu
        password: ${VIVO_PASSWORD}
```

Sources can also live in Consul (`config/harvest/...`), like any other setting. Keep secrets in
environment variables or Consul, never in the URL (a URL with user information fails the startup).

**Access to the platform.** The harvester writes with its own credentials, so Web Access Control
decides what it may create: give its agent `acl:Write` on `/harvested` (and nothing else). With
Keycloak, create a confidential client with a service account (`articulorium-harvest`) whose tokens
carry the audience the platform expects; they identify the agent
`info:oidc/agent/service-account-articulorium-harvest`. The default root ACL lets anyone write, so
the compose stack needs no credentials.

## Adding a source

1. Implement `HarvestSource`: `capabilities()` and `fetch(window, cursor)` returning pages of
   `HarvestedRecord`s and the cursor of the next page (`null` on the last one). Ask for
   `window.from()` when the source is incremental. Throw `HarvestException.recoverable(...)` for
   transient failures and `HarvestException.fatal(...)` for the others; report records that cannot
   be read as `RecordFailure`s in the page instead of dropping them. `SourceHttp` does the HTTP
   error classification, `KeysetCursor` the keyset paging, `DublinCore` the Dublin Core mapping.
2. Implement `HarvestSourceFactory` with a new `type()`, validating the options with
   `SourceOptions.requireKnown` (throw `IllegalArgumentException` for bad configuration), and make
   it a Spring `@Component` in `com.articulorium.harvest.source.<type>`.
3. Test it with `MockRestServiceServer` and recorded responses in `src/test/resources/fixtures`, as
   the existing sources do (paging, incremental requests, mapping, failures).

Another OAI-PMH metadata format only needs an `OaiMetadataMapper` bean for its prefix.

## Limitations and follow-ups

* Files are referenced, not copied: the bytes stay in the source.
* Links between records of one source keep pointing at the originals (only a record's own subject
  is renamed to its copy). VIVO profiles are stored below `/harvested`, not in the profile editor's
  container.
* Deletions are only seen where the protocol reports them (table above); a full reconciliation
  (removing copies of records a full harvest no longer lists) is not implemented.
* Reports are kept in memory per instance. Every instance runs the schedules: run one instance of
  the service.
* No user interface: harvests are run with the actuator endpoint.
* The native image of this service has not been built.

```bash
mvn -pl harvest -am verify
```
