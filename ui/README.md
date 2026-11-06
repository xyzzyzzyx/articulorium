# Articulorium UI

Angular 22 single page application for the Articulorium Linked Data Platform, at feature parity
with the Fedora Commons HTML interface. The `service` module serves it; the gateway exposes it at
`/ui/` (the bare gateway root redirects there).

## Features

| Fedora Commons UI                         | Articulorium UI                                                        |
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
| —                                         | **Sign in** with the OpenID Connect provider (Keycloak by default: authorization code flow with PKCE, tokens renewed and kept in session storage) or, with `auth.provider: token`, a pasted Consul ACL token; either is sent as `Authorization: Bearer` to the repository's services only |
| —                                         | **Communities**: navigate communities, sub-communities, collections and items; create them; build items from topologies |
| —                                         | **Profiles**: list, view, create, edit and delete academic profiles described with the VIVO ontology (identity, contact, ORCID iD, overview, research areas and keywords, positions, education, publications), with field-level validation |
| —                                         | **Topologies**: list built-in and custom topologies, view them, duplicate, edit (levels, PCDM types, metadata fields, files, child counts), delete |
| —                                         | **Topology aside** on resource pages: node, parent, ordered members, add children, edit the node's metadata fields, upload files per PCDM use; build a topology in any container |
| —                                         | **Notifications** (COAR Notify): received notifications with their JSON-LD, sent notifications with delivery status, send a notification |
| —                                         | **IIIF** link to the Manifest or Collection of PCDM objects, collections and communities |
| Versions                                  | Mementos of files show the technical metadata of that version (size, digest, media type, file name) |

## Languages

The interface is translated into every locale the service enables (12 by default, including
Arabic right to left); see [Internationalization](../README.md#internationalization).

* Texts are keys translated with [ngx-translate](https://github.com/ngx-translate/core)
  (`{{ 'nav.browse' | translate }}`, `I18nService.t()`), from `src/i18n/<tag>.json`; the English
  `en.json` is the reference and is also built into the bundle as the last-resort fallback.
* The service renders `index.html` in the request's locale (`lang`, `dir`) and inlines the
  locale's texts (`#ui-i18n`), merged over the default locale's: `I18nService` starts from that
  state, so the first frame is translated and matches the server-rendered document. Without it
  (`ng serve`) it asks `api/i18n`, and without the service it uses the built-in English texts.
* The toolbar's language menu writes the `articulorium-locale` cookie (read by every service) and
  switches texts in place, reloading only when the writing direction changes.
* Every request carries the locale as `Accept-Language` (`localeInterceptor`), so services answer
  in it; error messages a service only has in its default language (`Content-Language`) are
  preceded by the interface's translated summary of the status (`errorMessage`).
* Dates and numbers use `Intl` in the current locale (`l10nDate`, `l10nNumber`), counts the
  locale's plural rules (`tplural`, `I18nService.plural`); route titles, the paginator and
  sign-in (`ui_locales`) are translated too.
* RDF literals in another language than the interface keep their `lang` attribute; titles and
  descriptions are picked in the reader's language when a resource has several.

`npm run i18n:check` (part of the Maven build) fails when a shipped locale lacks a file, a key, a
placeholder or a plural form.

## How it talks to the platform

* Settings come from `api/config` (the service): `platformUrl` (default `../platform/`, the gateway
  route relative to `/ui/`), `title`, `apiDocsUrl` (`../swagger-ui.html`), `topologyUrl`
  (`../topology/`), `transformUrl` (`../transform/`), `communitiesPath` (`communities`) and
  `profilesPath` (`profiles`).
* Resources are read as N-Triples (`src/app/core/rdf/ntriples.ts`), which needs no prefix or
  JSON-LD context processing. An RDF request for a binary is redirected by the platform to its
  description, which is what the view shows; a `HEAD` on the binary supplies its `Allow` header.
* Write actions are shown only when the platform's `Allow` header permits them and its
  `WAC-Allow` header grants the mode.
* Fedora API extensions: `fcr:versions`, `fcr:versions/<id>`, `fcr:fixity`, `fcr:search`,
  `fcr:acl`, `fcr:inbox`, `fcr:outbox`; `/.well-known/webac` for the agent and its access.
* The topology service (`topologies`, `topologies/<id>/instances`, `topologies/<id>/members`) and
  the transform service's `iiif/<path>`.

Code layout: `core/` (configuration, `i18n/` (locale, translations, formatting pipes), the
`LdpService` HTTP client, RDF parsing and helpers,
`auth/` (the `AuthProvider` abstraction: OpenID Connect, pasted token, none; and the interceptor), `webac/`, `topology/`, `notify/`,
`profile/` (the VIVO mapping and validation of academic profiles)), `shared/` (term rendering,
property table, breadcrumb, dialogs), and one folder per view (`browse/`, `history/`, `search/`,
`permissions/`, `communities/`, `profiles/`, `topology/`, `notifications/`, `about/`).

## Development

```bash
npm ci
npm start                  # ng serve on http://localhost:4200, proxied to the gateway on :8000
npm test                   # unit tests (Vitest + jsdom)
npm run lint
npm run i18n:check         # every shipped locale has a complete translation
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
