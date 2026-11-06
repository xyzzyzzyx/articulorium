# Articulorium OAI-PMH service

An [OAI-PMH 2.0](https://www.openarchives.org/OAI/openarchivesprotocol.html) data provider that
exposes the repository's metadata to harvesters (aggregators, discovery services, other
repositories). It keeps no state of its own: every request is answered from the platform's
search API (`fcr:search`) and the RDF of the resources, with the harvester's credentials.

**Base URL:** `http://localhost:8000/oai` through the gateway (directly: `http://localhost:9007/`).

```bash
curl 'http://localhost:8000/oai?verb=Identify'
curl 'http://localhost:8000/oai?verb=ListSets'
curl 'http://localhost:8000/oai?verb=ListRecords&metadataPrefix=oai_dc&set=<setSpec>&from=2026-01-01'
curl 'http://localhost:8000/oai?verb=GetRecord&metadataPrefix=oai_dc&identifier=oai:articulorium.org:<path>'
```

GET and POST (`application/x-www-form-urlencoded`) are both accepted. Responses are `text/xml`
and valid against `OAI-PMH.xsd` and `oai_dc.xsd` (the tests check every response against the
official schemas).

## What is exposed

| OAI-PMH          | Articulorium                                                                     |
|------------------|----------------------------------------------------------------------------------|
| item / record    | a resource typed `record-type` (`pcdm:Object` by default): a book, a postcard, ... Objects inside another record (a book's pages) are part of it, not records of their own, unless `include-nested` is set |
| identifier       | `oai:<repository-identifier>:<path>`, e.g. `oai:articulorium.org:communities/arts/paintings/mona-lisa`; independent of the host name |
| datestamp        | the resource's last modification time, at second granularity                      |
| set              | every community (`art:Community`) and collection (`art:Collection`) below `/communities`; a record belongs to every community and collection it lies in, so harvesting a community includes its sub-communities and collections |
| setSpec          | the path below `/communities`, one component per segment: `arts:paintings` is the collection `communities/arts/paintings`. Characters other than letters, digits and `- _ . ~` are written as `!` + two hex digits per UTF-8 byte |
| metadata format  | `oai_dc` (unqualified Dublin Core) only                                           |
| deleted records  | `no`: deletions are not tracked (see below)                                       |

## Verbs

| Verb                  | Arguments                                                          | Notes                                         |
|-----------------------|--------------------------------------------------------------------|-----------------------------------------------|
| `Identify`            |                                                                    | `earliestDatestamp` is the earliest readable record's |
| `ListMetadataFormats` | `identifier`                                                       | always `oai_dc`; `idDoesNotExist` for unknown items |
| `ListSets`            | `resumptionToken`                                                  | `setName` is the `dcterms:title` (or `dc:title`, `rdfs:label`, `schema:name`) |
| `GetRecord`           | `identifier`, `metadataPrefix`                                     |                                               |
| `ListIdentifiers`     | `metadataPrefix`, `from`, `until`, `set` or `resumptionToken`      |                                               |
| `ListRecords`         | `metadataPrefix`, `from`, `until`, `set` or `resumptionToken`      |                                               |

`from` and `until` are inclusive and accept `YYYY-MM-DD` or `YYYY-MM-DDThh:mm:ssZ` (both the
same granularity). Every OAI-PMH error condition is answered as the protocol requires (HTTP 200
with `<error code="...">`): `badVerb`, `badArgument` (unknown, repeated, missing or malformed
arguments, `from` after `until`, mixed granularities), `badResumptionToken`,
`cannotDisseminateFormat`, `idDoesNotExist`, `noRecordsMatch` (also for an unknown set) and
`noSetHierarchy` (no communities or collections). For badVerb and badArgument the `request`
element carries no attributes. When the platform is unavailable the answer is HTTP 503 with
`Retry-After: 60`, which harvesters retry.

## Resumption tokens

Lists hold at most `page-size` items per response; longer lists end with a `resumptionToken`
(with a `cursor`), and the last part with an empty one. Tokens are opaque and stateless (they
encode the request and a position), so they never expire and survive restarts and load balancing.

Lists are ordered by modification time and path, and a token records a position *in time* (the
datestamp of the last resource covered, and how many resources with that exact time were covered)
rather than a running offset. Records created, changed or deleted during a harvest therefore never
shift the rest of the list: a harvest never skips a record. A record changed after it was answered
is answered again at the end of the list (with its new datestamp), which the protocol allows.

The platform's search orders ties by path, so positions are exact; it also leaves out results the
harvester may not read, which the position arithmetic accounts for (`RecordCatalog.advance`).

## Dublin Core mapping

Each element carries the values of the properties configured for it
(`articulorium.oai.dublin-core.<element>`), in this order:

| Element       | Default properties                                                                 |
|---------------|------------------------------------------------------------------------------------|
| `title`       | `dcterms:title`, `dc:title`, `dcterms:alternative`, `schema:name`                  |
| `creator`     | `dcterms:creator`, `dc:creator`, `schema:creator`, `schema:author`                 |
| `subject`     | `dcterms:subject`, `dc:subject`, `schema:keywords`                                 |
| `description` | `dcterms:description`, `dc:description`, `dcterms:abstract`, `dcterms:tableOfContents`, `schema:description` |
| `publisher`   | `dcterms:publisher`, `dc:publisher`, `schema:publisher`                            |
| `contributor` | `dcterms:contributor`, `dc:contributor`, `schema:contributor`                      |
| `date`        | `dcterms:date`, `dc:date`, `dcterms:issued`, `dcterms:created`, `schema:datePublished` |
| `type`        | `dcterms:type`, `dc:type`                                                          |
| `format`      | `dcterms:format`, `dc:format`, `dcterms:extent`                                    |
| `identifier`  | the UI landing page and the LDP resource IRI, then `dcterms:identifier`, `dc:identifier`, `dcterms:bibliographicCitation`, `bibo:isbn13`, `bibo:isbn10`, `bibo:issn`, `bibo:doi` |
| `source`      | `dcterms:source`, `dc:source`                                                      |
| `language`    | `dcterms:language`, `dc:language`, `schema:inLanguage`                             |
| `relation`    | `dcterms:relation`, `dc:relation`, `dcterms:isPartOf`, `dcterms:hasPart`, `dcterms:isVersionOf`, `dcterms:references`, `dcterms:isReferencedBy` |
| `coverage`    | `dcterms:coverage`, `dc:coverage`, `dcterms:spatial`, `dcterms:temporal`           |
| `rights`      | `dcterms:rights`, `dc:rights`, `dcterms:license`, `dcterms:accessRights`, `schema:license` |

Literals are written with their language tag (`xml:lang`), IRIs as written, blank nodes are left
out, and values of one property are sorted. Setting an element replaces its list; an empty list
drops it. Unknown element names stop the service at start-up.

## Access control

The harvester's `Authorization` header is passed to the platform, so Web Access Control decides
what each harvester sees: anonymous harvesters see the records everyone may read (with the default
root ACL, everything). Unreadable records are left out of lists and answer `idDoesNotExist`.

## Configuration (`articulorium.oai.*`)

| Property                | Default                         | Meaning                                                    |
|-------------------------|---------------------------------|------------------------------------------------------------|
| `platform-url`          | `http://articulorium-platform/` | where the platform is read (a Consul service with `load-balanced`) |
| `load-balanced`         | `true`                          | resolve `platform-url` through Consul                      |
| `platform-public-url`   | `../platform/`                  | where harvesters reach the platform, relative to the base URL or absolute |
| `ui-public-url`         | `../ui/`                        | where harvesters reach the user interface (landing pages) |
| `repository-name`       | `Articulorium`                  | Identify `repositoryName` (compose: `OAI_REPOSITORY_NAME`) |
| `repository-identifier` | `articulorium.org`              | namespace of item identifiers; keep it stable             |
| `admin-emails`          | `admin@example.org`             | Identify `adminEmail`s (compose: `OAI_ADMIN_EMAIL`)        |
| `page-size`             | `100`                           | items per response                                         |
| `record-type`           | `http://pcdm.org/models#Object` | `rdf:type` of records                                      |
| `include-nested`        | `false`                         | also expose records inside records                          |
| `communities-path`      | `communities`                   | container whose communities and collections are the sets    |
| `max-sets`              | `10000`                         | largest number of sets listed                              |
| `dublin-core.<element>` | see above                       | RDF properties of each Dublin Core element                  |

Metrics: `oai.requests` counts requests by `verb` and `outcome` (`ok` or the OAI-PMH error code;
`unavailable` for 503s). The OpenAPI description is at `/v3/api-docs` (listed by the gateway's
Swagger UI as `oai`).

## Limitations and follow-ups

* **Deleted records** are not reported (`deletedRecord=no`): the platform deletes resources
  outright. Supporting `persistent` or `transient` needs tombstones, for example recorded from the
  resource bus' delete events.
* Only `oai_dc`. Richer formats (MODS, `oai_datacite`, ...) can be added next to `DublinCoreMapper`.
* No `completeListSize`: the platform's counts include records a harvester may not read.
