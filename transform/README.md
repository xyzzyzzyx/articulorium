# Transform service

Two jobs:

1. **Search documents.** For every resource change on the resource bus, run each active
   `Transformer` bean against the resource's RDF and publish the merged document on the document
   bus for the index service.
2. **IIIF exports.** Serve PCDM structures read from the platform as
   [IIIF Presentation 3.0](https://iiif.io/api/presentation/3.0/) Manifests and Collections.

The gateway serves the service under `/transform/`.

## Transformers

The built-in `field-mapping` transformer maps RDF property paths to typed fields
([field-mappings.yml](src/main/resources/transform/field-mappings.yml)). Add one by contributing a
Spring bean implementing `com.articulorum.transform.spi.Transformer` and listing its name in
`articulorum.transform.transformers`. `POST /transformers/<name>/preview?iri=…` runs one on a posted
RDF document without publishing anything.

## IIIF

`GET /iiif/<resource path>` (through the gateway: `/transform/iiif/<path>`):

| Resource                                                     | Export     |
|--------------------------------------------------------------|------------|
| `pcdm:Object` (a book, an issue, a postcard, ...)            | Manifest   |
| `pcdm:Collection`, `art:Community`, `art:Collection`, `art:CommunityRoot` | Collection |

* **Canvases**: every object in the member tree (`pcdm:hasMember`, ordered by `schema:position`)
  that has no members of its own. Its image, audio or video file (`pcdm:hasFile`, preferring
  `pcdmuse:ServiceFile`, then `pcdmuse:OriginalFile`) is painted on the canvas; image dimensions
  are read from the image's first bytes (JPEG, PNG, GIF, BMP, TIFF), durations from
  `ebucore:duration` or a numeric `dcterms:extent`. Other files (PDF, OCR text, transcripts) are
  `rendering`s.
* **Ranges**: intermediate objects (a postcard's front and back, a folio's recto and verso) become
  `structures`.
* **Descriptive metadata**: every descriptive property becomes a `metadata` entry;
  `dcterms:title` is the label, `dcterms:description`/`abstract` the summary, `dcterms:rights` the
  required statement and a Creative Commons or RightsStatements.org `dcterms:license` the rights.
  `seeAlso` links the LDP resource, `homepage` its page in the user interface, `thumbnail` the first
  image.
* **Collections** list their members (PCDM membership, or containment for the community
  structure) as Manifests and Collections.

Documents carry `Access-Control-Allow-Origin: *` for viewers on other origins. The caller's
`Authorization` header is passed on to the platform (Web Access Control applies), and the platform
is read with `X-Forwarded-*` headers describing the caller's public platform URL, so every IRI in
the export is the public one.

| Setting (`articulorum.iiif.*`) | Default                        | Meaning                                       |
|--------------------------------|--------------------------------|-----------------------------------------------|
| `platform-url`                 | `http://articulorum-platform/` | where the service reads the platform          |
| `load-balanced`                | `true`                         | resolve the host as a Consul service name     |
| `platform-public-url`          | `../platform/`                 | the platform as clients see it, relative to this service's public base |
| `ui-public-url`                | `../ui/`                       | the user interface as clients see it          |
| `probe-image-size`, `probe-bytes` | `true`, `262144`            | read image dimensions from the leading bytes  |
| `default-width`, `default-height` | `1000`                      | canvas size when unknown                      |
| `max-resources`                | `2000`                         | largest number of resources read per export   |
