# Articulorium discovery layer

The public face of the repository: search, the community structure, and a page for every
community, collection and object, presenting its IIIF export in
[Mirador](https://projectmirador.org/). Angular 22 rendered on the server (Angular SSR on
Node.js) with Angular Material 3, themed and laid out by the [discovery service](../discovery/README.md)'s
settings, so deployments change branding, layouts, facets and search precedence without touching
this code. The gateway serves it under `/discover/`.

## Pages

| URL (below `/discover/`)  | Page |
|---------------------------|------|
| `` (empty)                | redirects (302, `Vary: Accept-Language`) to the home page in the visitor's language |
| `<lang>/`                 | home: introduction, search, top-level communities and collections, recent objects (configured sections, in order) |
| `<lang>/search?q=&f=&filter=&sort=&page=` | results (list or grid), facets and filters as links, active refinements, sort orders, pages |
| `<lang>/items/<path>`     | a resource: trail, title, viewer, description, metadata, IIIF link; members of communities and collections |
| `robots.txt`, `sitemap.xml` | generated from the configuration and the public resources |

Every page lives below its locale, so each language has its own URL for search engines; the
language menu links to the same page in the other locales. Tags in another case (`pt-br`) redirect
to the configured spelling; unknown pages answer 404.

## Server-side rendering and search engines

* Every page is rendered on the server for each request (`RenderMode.Server`), then hydrated; the
  answers the server fetched are handed to the browser (HTTP transfer cache), so hydration does not
  ask the API again.
* The head carries the title, description, canonical URL, `hreflang` alternates for every locale
  and `x-default`, robots directives (search pages `noindex, follow`), Open Graph and Twitter card
  properties (the resource's IIIF thumbnail as image) and schema.org JSON-LD: `WebSite` with a
  `SearchAction` on the home page; `Book`, `ScholarlyArticle`, `Collection`, ... with creators,
  subjects, dates, license, the IIIF Manifest and `isPartOf`, plus a `BreadcrumbList`, on resource
  pages. Values are escaped so none can close the script element.
* Missing, excluded and private resources answer **404**, an unavailable discovery service or index
  **503** (`Cache-Control: no-store`); pages are otherwise `Cache-Control: public, max-age=60`.
* `<html lang dir>` follows the locale (Arabic right to left).

## Theming and layouts

`ThemeService` writes the deployment's theme into `<style id="disc-theme">`: Material 3 system
tokens (`--mat-sys-primary`, ...; light and dark values become `light-dark()`), fonts
(`--disc-brand-font`, `--disc-plain-font`), base size, content width, corner radii, the color
scheme, the density class (`disc-density-1` … `-4`) and the deployment's CSS; the fonts' style sheet
is linked first and the deployment's style sheet last. The server renders all of it, so the first
frame is themed. Layout options (home sections, list or grid, facet position, viewer position and
height, metadata fields) come from the same configuration.

## Mirador

Resource pages with a IIIF export open it in Mirador 4 (images with deep zoom, audio and video,
Collections with their picker), in the interface's language and colors. Mirador, React and Material
UI are a separate chunk loaded only in browsers, only on pages with a viewer; the server renders the
preview image instead.

PDFs: the transform service paints the PDF of a canvas without images or media as a `Text` body
(`application/pdf`), which Mirador hands to its `TextViewer`; the `ArticuloriumPdfViewer` plugin
(`viewer/mirador-pdf-plugin.ts`) wraps it and shows the browser's PDF viewer in an `<object>`,
with a link to open the document where browsers cannot embed it, above Mirador's canvas navigation.

Mirador renders its workspace as a `main` element; the viewer component exposes it as a region so
the page keeps a single main landmark (an accessibility checker reports the changed role as a minor
`aria-allowed-role` issue inside Mirador).

## Accessibility

Skip link, landmarks (banner, navigation, search, main, complementary facets, contentinfo), one
`h1` per page and an ordered heading structure, facets and pages as real links with
`aria-current`, labelled search fields, visible focus, text alternatives for images (decorative
thumbnails are empty), `prefers-reduced-motion`, right-to-left layouts. axe-core (WCAG 2.1 AA and
best practices) reports no issues on the home, search and resource pages apart from the Mirador
note above.

## Languages

Texts are ngx-translate keys from `src/i18n/<tag>.json` for every shipped locale, each loaded as its
own chunk (no HTTP request, on the server too); `en.json` is the reference. Labels of the settings
(`{ key, value, translations }`) are resolved in the current language. Dates, numbers, plural forms
(`Intl`) and language names follow the locale. `npm run i18n:check` (part of the Maven build) fails
when a shipped locale lacks a file, a key, a placeholder or a plural form.

## Configuration of the server

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `4000` | port of the Node.js server |
| `DISCOVERY_API_URL` | `http://localhost:8000/discover/api/` | the discovery API from this server: the same path as browsers use, on an internal address (compose: `http://gateway:9000/discover/api/`) |
| `NG_ALLOWED_HOSTS` | `localhost,127.0.0.1,discovery-ui,gateway` (build) | host names requests may be addressed to (`Host`, `X-Forwarded-Host`); add the public host name |
| `NG_TRUST_PROXY_HEADERS` | the gateway's `X-Forwarded-Host/-Proto/-Port/-Prefix` | proxy headers to trust |

The application's base href is `/discover/` (build option `baseHref`); serving it elsewhere needs a
build with another base href and the gateway route moved accordingly.

## Code layout

`core/` (configuration, the discovery API client, `i18n/` (locale service, negotiation, guards,
translations), `seo/` (metadata and structured data), `theme/`, `http/` (response status)),
`layout/` (header, footer, search field, language menu), `pages/` (home, search with facet panel,
item, not found), `viewer/` (Mirador component, configuration, loader, PDF plugin), `shared/`,
and `server.ts` with `server/` (robots.txt, sitemap, API address).

## Development

```bash
npm ci
npm start              # ng serve on http://localhost:4200/discover/, API proxied to the gateway on :8000
npm test               # unit tests (Vitest + jsdom)
npm run lint
npm run i18n:check
npm run build          # dist/discovery-ui/{browser,server}
npm run serve:ssr      # the built server on :4000
```

Maven builds, lints and tests this module (`mvn -pl discovery-ui verify`) with the Node.js it
downloads into the repository's `node/`; `-Dskip.npm` skips it. The Docker image
(`discovery-ui/Dockerfile`, built by `docker compose`) runs the server bundle, which includes its
dependencies.

End-to-end tests: [`e2e/cypress/e2e/discovery.cy.ts`](../e2e/cypress/e2e/discovery.cy.ts).
