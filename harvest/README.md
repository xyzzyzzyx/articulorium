# Articulorium harvest service

Harvests records from external repositories and ingests them into the platform: Fedora Commons
(and other Articulorium instances), DSpace, VIVO and any OAI-PMH 2.0 data provider. Every source
is configured with an identifier, a type and an endpoint; its records are mapped to one shared
representation and stored as resources below `/harvested/<source>/`, replaced on every harvest.

Harvests run on a schedule per source (incremental) or on demand: from the user interface's
**Harvest** page (the [harvest API](#the-harvest-api-and-the-user-interface), `/harvest/sources` on
the gateway, protected by the platform's Web Access Control), or through the `harvest` actuator
endpoint on the management port, which the gateway does not expose.

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
| `file-pattern`, `file-url` |  | the files of a record, derived from its identifiers (Dublin Core names none): every `dc:identifier` the regular expression `file-pattern` matches as a whole yields the file at `file-url`, where `{1}`, `{2}` … stand for the pattern's groups. arXiv: `https?://arxiv[.]org/abs/(.+)` → `https://export.arxiv.org/pdf/{1}` |
| `file-media-type` |          | the media type of those files (`application/pdf`)                      |

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

## The harvest API and the user interface

The user interface's **Harvest** page (`/ui/harvest`, in the main navigation) lists every source
with what it supports (incremental or full harvests only, deletions reported or not, its schedule,
whether it is disabled), starts incremental and full harvests (a full harvest is confirmed first),
cancels a running harvest (confirmed; it stops after the record in progress), follows a running
harvest's progress (the page reads the sources again every 3 seconds while one runs) and shows the
latest reports: outcome, mode, start, duration, window, the counts, the checkpoint, the reason of a
failed or cancelled harvest and the failed records. Only `COMPLETED` is shown as a success;
`COMPLETED_WITH_ERRORS`, `FAILED` and `CANCELLED` say that the checkpoint did not move.

The page uses the harvest API, served on the service port (`server.port`, 9000) and routed by the
gateway under `/harvest` (`articulorium.ui.harvest-url` tells the user interface where, default
`../harvest/`):

| Request                                         | Effect                                                     | Needs |
|-------------------------------------------------|------------------------------------------------------------|-------|
| `GET /sources`                                  | `{"sources": [...], "operate": true}`: every source (as the actuator endpoint shows it) and whether the caller may start and cancel harvests | `read` |
| `GET /sources/{source}`                         | one source (404 when unknown)                              | `read` |
| `POST /sources/{source}/harvests` `{"mode": "full"}` | starts a harvest (202); `mode` is `incremental` (default) or `full`; 409 when the source is disabled or already being harvested, 400 for another mode | `write` |
| `DELETE /sources/{source}/harvests/running`     | stops the running harvest after the record in progress (202; 409 when none) | `write` |

```bash
curl -s -H "Authorization: Bearer $TOKEN" http://localhost:8000/harvest/sources
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"mode":"incremental"}' http://localhost:8000/harvest/sources/self-oai/harvests
```

**Permissions.** The service decides nothing itself: it presents the caller's own `Authorization`
header (an OpenID Connect token, a Consul token, whatever the platform accepts) to the platform's
`/.well-known/webac?resource=<container>` and requires `read` on the container of the harvested
records (`/harvested` by default) to view sources and reports, and `write` on it to start and
cancel harvests. The harvester's own platform credentials are never used for this. Refusals are RFC
9457 problem documents: 401 (with `WWW-Authenticate: Bearer`) for an anonymous caller without the
mode or credentials the platform refuses, 403 for a known caller without the mode, 503 when the
platform cannot answer. The service logs who started or cancelled each harvest.

With the default root ACL everyone may read and write, so everyone may run harvests. To give
harvesting to a group, set an ACL on `/harvested` (Permissions page, or `fcr:acl`) that grants
`acl:Read` to those who may follow harvests and `acl:Write` to those who may run them, inherited by
the records (`acl:default`), and keep the harvester's own agent (`platform-auth`) able to write
there. Readers see the page without its actions.

Sources are shown without credentials: `username`, `password` and `token` never leave the service
(and a source URL with user information is refused at startup). Failure reasons are
diagnostics written for operators (they name the record and the HTTP status) and contain no
credentials. Set `articulorium.harvest.api.enabled=false` to turn the API off (the page then says
the harvest service cannot be reached); the actuator endpoint stays.

### Files of placed records (IIIF and the discovery layer)

A harvested record references its files where the source keeps them (`art:harvestFile`). For a
source with `copy-files: true`, a record that is placed in a collection (accepted automatically or
from the review queue) gets them as files of its item: they are downloaded and stored in the item's
`files` container, an LDP direct container that makes each a `pcdm:hasFile` of the item, exactly as
for items built in the user interface. The item's IIIF manifest (`/transform/iiif/<item>`) then
paints its images and its PDF, and the discovery layer shows them in its viewer; nothing else is
needed. Files are copied once (they are named after their address); one that could not be copied is
tried again whenever the record is harvested again, so a **full harvest** also gives the items placed
before this was turned on their files.

Copying never fails a harvest or a placement. A file is left referenced, logged and counted
(`harvest.files` by `source` and `outcome`: `copied`, `too-large`, `skipped`, `failed`, `refused`)
when it is larger than `files.max-size`, of a media type that is not copied, missing, a web page
served in place of the announced file, or on a host the source's settings do not name. Files are
only downloaded from the host of the source's `url` and of its `file-url` option, over http(s) and
without credentials: file addresses come from the source's metadata, which must not be able to make
the harvester fetch from the deployment's own network. Mind the rights of what you copy: a
repository shows these files to everyone who may read the item.

### Review queue (classification by the AI agent)

With `articulorium.harvest.classification.enabled=true` (on in the compose stack) the AI agent
([ai/README.md](../ai/README.md#classifying-harvested-records)) suggests where every new record
belongs, `GET /sources` answers `"classification": true`, and the Harvest page shows the review
queue below the sources. With `classification.auto-accept` (the default; a source decides for
itself with `sources.<id>.auto-accept`) suggestions are accepted at once, creating the proposed
communities, collections and topologies (`classification.create-communities`,
`create-collections`, `create-topologies`, all on); `GET /sources` lists the sources that do in
`autoAccepted`. The queue then holds what could not be accepted automatically, and the accepted
records (decided by `auto`) under "all".
Records are ingested exactly as without the agent; a record the agent
cannot classify is kept as `FAILED` with the reason and never fails the harvest. A **full harvest**
classifies again every record whose classification failed or found no placement (suggestions
waiting for review, rejected and placed records are left alone). A suggested topology whose root
is a collection (a journal, a newspaper title) is not used for automatic acceptance: the record
becomes a plain object.

| Request | Effect | Needs |
|---------|--------|-------|
| `GET /classifications?source=&status=open\|all&offset=&limit=` | a page of classifications, most recently changed first (`limit` at most 100), with `decide`: whether the caller may act on them | `read` |
| `GET /classifications/{source}/{record}` | one classification | `read` |
| `POST /classifications/{source}/{record}/acceptance` `{"collection": "...", "topology": "book"\|"none"}` | places the record in the suggested collection (both fields optional) or another one, as an item of the topology | `write` |
| `POST /classifications/{source}/{record}/rejection` | the record stays where it was harvested | `write` |
| `POST /classifications/{source}/{record}/retry` | classifies the record again (with the harvester's credentials) | `write` |

A classification is `PENDING`, `PLACING` (an interrupted placement resumes on the same item),
`ACCEPTED`, `REJECTED` or `FAILED`; suggestions below `review-threshold` are `uncertain`. Placing
writes to the collection with the caller's own credentials. Refusals are problem documents with a
`reason`: `UNKNOWN_SOURCE` and `NOT_FOUND` (404), `DECIDED`, `TARGET_REQUIRED` (the suggestion names
no existing collection, so the request must) and `TOPOLOGY_REQUIRED` (the suggestion proposes a new
topology, so the request must name an existing one or `none`) (409), `NOT_PLACED` (422: the
collection or topology cannot be used, or the platform refused), `REFUSED` (the platform's status) and
`PLATFORM_UNAVAILABLE` (503).

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
| `api.enabled`                    | `true`                         | serve the harvest API of the user interface (`/sources`)      |
| `concurrency`                    | `2`                            | harvests running at once (others wait)                        |
| `sources.<id>.type`              |                                | `oai-pmh`, `dspace`, `fedora` or `vivo`                       |
| `sources.<id>.url`               |                                | the endpoint (no credentials in it)                           |
| `sources.<id>.enabled`           | `true`                         | whether it may be harvested                                   |
| `sources.<id>.schedule`          |                                | cron expression (seconds first) of incremental harvests; on demand only when empty |
| `sources.<id>.harvest-on-startup` | `false`                       | start an incremental harvest when the service is ready (after a restart it continues from the checkpoint) |
| `sources.<id>.initial-window`    |                                | how far back incremental harvests reach while the source has no checkpoint, e.g. `P7D`; every record when unset (full harvests are never limited) |
| `sources.<id>.page-size`         | `100`                          | records asked for per page where the protocol lets the client choose |
| `sources.<id>.username`, `password`, `token` |                    | credentials (see each type)                                   |
| `sources.<id>.options.*`         |                                | type options (above); unknown options fail the startup       |
| `sources.<id>.copy-files`        | `false`                        | copy the files of the source's records into their items when they are placed in a collection (below) |
| `files.max-size`, `files.max-per-record` | `50MB`, `5`            | largest file and number of files copied per record; the rest stays referenced |
| `files.delay`                    | `1s`                           | pause before each download after the first                    |
| `files.media-types`              |                                | media types copied (`application/pdf`, `image/*` …); any when empty |
| `sources.<id>.classify`          | `true`                         | classify the source's records when classification is enabled  |
| `sources.<id>.auto-accept`       | `classification.auto-accept`   | place the source's classified records without a person        |
| `classification.enabled`         | `false`                        | classify new records with the AI agent ([ai/README.md](../ai/README.md#classifying-harvested-records)) |
| `classification.timeout`         | `15m`                          | how long the harvester waits for one answer of the agent; too short for the model's speed records classifications as `AGENT_UNAVAILABLE` while the agent is still working |
| `classification.auto-accept`     | `true`                         | accept suggestions without a person, for sources that do not decide themselves |
| `classification.auto-accept-threshold` |                          | accept automatically only from this confidence (0 to 1)       |
| `classification.create-communities`, `create-collections`, `create-topologies` | `true` | what automatic acceptance creates when the agent proposes it; `false` leaves such suggestions for review |

```yaml
articulorium:
  harvest:
    sources:
      arxiv:
        type: oai-pmh
        url: https://oaipmh.arxiv.org/oai
        schedule: "0 30 4 * * *"      # every night, after arXiv's daily update
        harvest-on-startup: true
        initial-window: P7D           # the first harvest reads the last week only
        auto-accept: true             # classified records are placed at once (compose default)
        copy-files: true              # the PDF of a placed record is copied into its item
        options:
          set: cs:cs:DL               # group:archive:CATEGORY; "cs" is every computer science paper
          file-pattern: "https?://arxiv[.]org/abs/(.+)"
          file-url: "https://export.arxiv.org/pdf/{1}"     # arXiv's host for programmatic access
          file-media-type: application/pdf
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

* Files are copied only for sources with `copy-files`, only when a record is placed in a collection,
  and without the source's credentials (public files); a file replaced in the source under the same
  address is not copied again.
* Links between records of one source keep pointing at the originals (only a record's own subject
  is renamed to its copy). VIVO profiles are stored below `/harvested`, not in the profile editor's
  container.
* Deletions are only seen where the protocol reports them (table above); a full reconciliation
  (removing copies of records a full harvest no longer lists) is not implemented.
* Reports are kept in memory per instance. Every instance runs the schedules: run one instance of
  the service.
* Reports are in memory: after a restart the page shows no history until the next harvest.
* Access to harvests is per container (`/harvested`), not per source.
* The native image of this service has not been built.

```bash
mvn -pl harvest -am verify
```
