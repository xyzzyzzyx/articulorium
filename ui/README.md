# Articulorum UI

Angular 22 single page application for the Articulorum Linked Data Platform, at feature parity
with the Fedora Commons HTML interface. The `service` module serves it; the gateway exposes it at
`/ui/` (the bare gateway root redirects there).

## Features

| Fedora Commons UI                         | Articulorum UI                                                        |
|-------------------------------------------|-----------------------------------------------------------------------|
| Breadcrumb, Home and Search navigation    | Breadcrumb on every view; Browse, Search, API (Swagger UI) and About |
| Created / last modified (and by whom)     | Same, from the `fedora:` server-managed triples                       |
| Children list with count                  | Same, sorted, linking to each child                                   |
| Properties and "Other Resources"          | Property table per subject (prefixed names, links), blank nodes in panels |
| Create new child resource                 | Basic / direct / indirect container (Turtle, with membership templates) or binary; optional identifier (PUT with `If-None-Match: *`) or minted (POST + Slug) |
| Update Properties (SPARQL Update)         | Same, pre-filled with the `PREFIX`es the resource uses, sent with `If-Match` |
| Download Content, Update Content, Fixity  | Same; the media type of uploads is detected by the browser or, failing that, by Apache Tika in the service |
| Versioning: create, view versions         | Same; version list with agent and message, historic view with download of that version's bytes |
| Delete resource (with confirmation)       | Same, then returns to the parent                                      |
| Search (field / operator / term, paging)  | Same, any number of conditions, ordering; the search is in the URL    |
| Error modal                               | Snack bar with the RFC 9457 problem detail; error card for missing resources |

Archival groups and WebAC (ACL) links are not offered because the platform does not implement
them.

## How it talks to the platform

* Settings come from `api/config` (the service): `platformUrl` (default `../platform/`, the gateway
  route relative to `/ui/`) and `title`.
* Resources are read as N-Triples (`src/app/core/rdf/ntriples.ts`), which needs no prefix or
  JSON-LD context processing. An RDF request for a binary is redirected by the platform to its
  description, which is what the view shows; a `HEAD` on the binary supplies its `Allow` header.
* Write actions are shown only when the platform's `Allow` header permits them.
* Fedora API extensions: `fcr:versions`, `fcr:versions/<id>`, `fcr:fixity`, `fcr:search`.

Code layout: `core/` (configuration, the `LdpService` HTTP client, RDF parsing and helpers),
`shared/` (term rendering, property table, breadcrumb, dialogs), and one folder per view
(`browse/`, `history/`, `search/`, `about/`).

## Development

```bash
npm ci
npm start                  # ng serve on http://localhost:4200, proxied to the gateway on :8000
npm test                   # unit tests (Vitest + jsdom)
npm run lint
npm run build              # production build in dist/ui/browser
```

`ng serve` uses `proxy.conf.json`: `/platform` and `/api` go to the gateway
(`docker compose up` in the repository root), with `X-Forwarded-*` headers so the platform mints
IRIs under `http://localhost:4200/platform/`.

Maven builds this module (`mvn -pl ui package`) with a Node.js runtime it downloads itself and
packages `dist/ui/browser` as static resources for the service module. `-Dskip.npm` skips the npm
steps and reuses an existing `dist/`.

The build is self-contained: the Material icon font is bundled (`material-design-icons-iconfont`)
and text uses the system font stack, so no external font service is contacted at build or run
time.

## End-to-end tests

The Cypress suite lives in [`../e2e`](../e2e/README.md) and runs from the `e2e` docker compose
service against the running stack: `docker compose --profile e2e run --rm e2e`.
