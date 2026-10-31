# Articulorum UI

Angular 22 single page application for the Articulorum Linked Data Platform, at feature parity
with the Fedora Commons HTML interface. The `service` module serves it; the gateway exposes it at
`/ui/` (the bare gateway root redirects there).

## Features

| Fedora Commons UI                         | Articulorum UI                                                        |
|-------------------------------------------|-----------------------------------------------------------------------|
| Breadcrumb, Home and Search navigation    | Breadcrumb on every view; Browse, Communities, Topologies, Search, API (the gateway's Swagger UI for every service) and About |
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
| Archival groups                           | "Archival group" option when creating containers; badges on groups and their members; members point to versioning the group |
| WebAC (ACL)                               | Your access modes on every resource (`WAC-Allow`), actions hidden when not allowed; **Permissions** page: effective rules and where they come from, grant (everyone, authenticated agents, an agent or a group; modes; inheritance) and revoke, remove the ACL to inherit again |
| —                                         | **Sign in** with a Consul ACL token (sent as `Authorization: Bearer` to the repository's services only, kept in session storage) |
| —                                         | **Communities**: navigate communities, sub-communities, collections and items; create them; build items from topologies |
| —                                         | **Topologies**: list built-in and custom topologies, view them, duplicate, edit (levels, PCDM types, metadata fields, files, child counts), delete |
| —                                         | **Topology aside** on resource pages: node, parent, ordered members, add children, edit the node's metadata fields, upload files per PCDM use; build a topology in any container |
| —                                         | **Notifications** (COAR Notify): received notifications with their JSON-LD, sent notifications with delivery status, send a notification |
| —                                         | **IIIF** link to the Manifest or Collection of PCDM objects, collections and communities |
| Versions                                  | Mementos of files show the technical metadata of that version (size, digest, media type, file name) |

## How it talks to the platform

* Settings come from `api/config` (the service): `platformUrl` (default `../platform/`, the gateway
  route relative to `/ui/`), `title`, `apiDocsUrl` (`../swagger-ui.html`), `topologyUrl`
  (`../topology/`), `transformUrl` (`../transform/`) and `communitiesPath` (`communities`).
* Resources are read as N-Triples (`src/app/core/rdf/ntriples.ts`), which needs no prefix or
  JSON-LD context processing. An RDF request for a binary is redirected by the platform to its
  description, which is what the view shows; a `HEAD` on the binary supplies its `Allow` header.
* Write actions are shown only when the platform's `Allow` header permits them and its
  `WAC-Allow` header grants the mode.
* Fedora API extensions: `fcr:versions`, `fcr:versions/<id>`, `fcr:fixity`, `fcr:search`,
  `fcr:acl`, `fcr:inbox`, `fcr:outbox`; `/.well-known/webac` for the agent and its access.
* The topology service (`topologies`, `topologies/<id>/instances`, `topologies/<id>/members`) and
  the transform service's `iiif/<path>`.

Code layout: `core/` (configuration, the `LdpService` HTTP client, RDF parsing and helpers,
`auth/` (token and interceptor), `webac/`, `topology/`, `notify/`), `shared/` (term rendering,
property table, breadcrumb, dialogs), and one folder per view (`browse/`, `history/`, `search/`,
`permissions/`, `communities/`, `topology/`, `notifications/`, `about/`).

## Development

```bash
npm ci
npm start                  # ng serve on http://localhost:4200, proxied to the gateway on :8000
npm test                   # unit tests (Vitest + jsdom)
npm run lint
npm run build              # production build in dist/ui/browser
```

`ng serve` uses `proxy.conf.json`: `/platform`, `/topology`, `/transform` and `/api` go to the gateway
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
