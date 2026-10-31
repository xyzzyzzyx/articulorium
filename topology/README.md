# Topology service

Definitions of PCDM structures ("topologies") and their instantiation in the Linked Data
Platform. The gateway serves the API under `/topology/`; the UI uses it on the **Topologies** page,
in the **Topology** aside of resource pages and on the **Communities** page.

## Topologies

A topology is a tree of nodes. Each node is a PCDM `Collection` or `Object` with

* a key and a label (`page`, `Page`),
* additional `rdf:type`s (`bibo:Book`),
* metadata fields (predicate, label, required, repeatable, default value, datatype),
* file uses (`pcdmuse:OriginalFile`, `pcdmuse:ServiceFile`, `pcdmuse:ExtractedText`, ...),
* child rules: the child node, minimum and maximum number (`-1`: unlimited), how many a build
  creates (`initial`) and the name pattern of the children (`page-{n}`).

Built-in topologies ship as JSON in [`src/main/resources/topologies`](src/main/resources/topologies):

| Id            | Structure                                                                  |
|---------------|----------------------------------------------------------------------------|
| `book`        | Book object (`bibo:Book`, PDF) → pages (master and access image, OCR text) |
| `newspaper`   | Newspaper collection → issues (`bibo:Issue`, PDF) → pages                  |
| `journal`     | Journal collection → volumes → issues → articles (PDF, HTML, supplementary data) |
| `postcards`   | Postcard collection → postcards → front and back (image, transcription)    |
| `manuscript`  | Manuscript object → folios → recto and verso (image, transcription)        |
| `audiovisual` | Recording object (thumbnail) → parts (preservation, access file, transcript) |

Custom topologies are stored in the repository itself, one JSON document per topology at
`/topologies/<id>` (`articulorum.topology.container`), and are validated on save (slug keys used
once, PCDM objects cannot contain collections, consistent counts, at most 8 levels and 100 nodes).
Built-in topologies cannot be changed; duplicate one instead.

## Building

`POST /topologies/<id>/instances` with `{"parent": "<container path>", "title": "...", "counts":
{"page": 12}}` creates, in the platform:

```
<parent>/<slug>                       ldp:BasicContainer  a pcdm:Object, bibo:Book ;
                                                          dcterms:title "..." ; art:topology "book" ;
                                                          art:topologyNode "book" ; default metadata
<parent>/<slug>/members               ldp:DirectContainer ldp:membershipResource <..> ;
                                                          ldp:hasMemberRelation pcdm:hasMember
<parent>/<slug>/members/page-1        ldp:BasicContainer  a pcdm:Object ; schema:position 1 ; ...
<parent>/<slug>/members/page-1/files  ldp:DirectContainer ldp:hasMemberRelation pcdm:hasFile
```

`POST /topologies/<id>/members` with `{"parent": "<instance path>", "nodeKey": "page"}` adds one
child at the next position (with its own default children), respecting the rule's maximum.

The caller's `Authorization` header is passed on to the platform, so Web Access Control decides
what may be created; refusals keep their status (401, 403, 404, 409).

## API

| Request                                   | Effect                                     |
|-------------------------------------------|--------------------------------------------|
| `GET /topologies`                         | summaries, built-in first                  |
| `GET /topologies/<id>`                    | one topology                               |
| `POST /topologies`                        | create a custom topology                   |
| `PUT /topologies/<id>`                    | create or replace a custom topology        |
| `DELETE /topologies/<id>`                 | delete a custom topology (built resources stay) |
| `POST /topologies/<id>/instances`         | build an instance                          |
| `POST /topologies/<id>/members`           | add a child to an instance                 |

OpenAPI: `/v3/api-docs` (listed in the gateway's `/swagger-ui.html`).

## Settings (`articulorum.topology.*`)

| Setting          | Default                          | Meaning                                         |
|------------------|----------------------------------|-------------------------------------------------|
| `platform-url`   | `http://articulorum-platform/`   | the platform's LDP root                         |
| `load-balanced`  | `true`                           | resolve the host as a Consul service name       |
| `container`      | `topologies`                     | container of custom topologies                  |
| `max-children`   | `500`                            | largest number of children of one kind per build |

Requests to the platform carry `X-Forwarded-*` headers describing `platform-url`, so the platform
mints every IRI under it whichever instance answers.
