# Articulorium discovery service

The API of the public discovery and presentation layer ([discovery-ui](../discovery-ui/README.md)).
It serves the deployment's configuration (identity, theme, layouts, viewer, facets, filters, sort
orders, search engine directives, locales), searches the Solr collection the index service keeps in
sync, describes single resources for their pages, and lists the public resources for sitemaps. It
holds no state of its own.

The gateway serves it under `/discover/api/` (its OpenAPI description is `/discover/api/docs`,
listed in the gateway's Swagger UI).

| Endpoint            | Answer                                                                    |
|---------------------|---------------------------------------------------------------------------|
| `GET /api/config`   | the public part of the settings below, the enabled locales and the public addresses |
| `GET /api/search`   | a page of results the caller may read, with facets: `q`, `page`, `size`, `sort`, `f=<facet>:<value>` (repeated), `filter=<id>` (repeated), `within=<container path>`, `kind=community\|collection\|object`, `lang` |
| `GET /api/item?path=<path>&lang=` | a resource: title and description in the caller's language, configured metadata fields, trail of communities and collections, a summary of its IIIF export |
| `GET /api/sitemap`  | the resources anonymous visitors may read, most recently modified first   |

Refusals are RFC 9457 problems (`Content-Language: en`): 400 for malformed requests (unknown sort
orders, searches over 500 characters, paging past 10,000 results), 404 for resources that do not
exist, are outside the discovery layer or are private (not told apart), 503 while Solr is
unavailable.

## Who sees what

The Solr collection holds every resource, whatever its access rules. Every search hit, every
resource page, every parent of the trail and every sitemap entry is therefore checked against the
platform's Web Access Control: the caller's `Authorization` header (none for anonymous visitors
and for server-side rendering) is presented to `/.well-known/webac?resource=<path>`, and only
resources the platform grants `read` are returned. Refusals, errors and an unreachable platform
all hide the resource. Answers for anonymous visitors are cached for
`access.anonymous-cache-seconds` (60), which bounds how long a resource stays visible after it was
made private.

Hit counts (`total`) and facet counts come from Solr and include resources the caller may not read;
a page lists fewer results than its size when some are hidden (`hidden`). Keep private resources
out of the discovery layer with `search.base-filters` when even their facet values must not show.

## Configuration

Every setting is in `application.yml` and can be changed per deployment with environment variables
(`ARTICULORIUM_DISCOVERY_PUBLIC_URL`) or in Consul (`config/discovery/articulorium/discovery/...`).
Values are checked at start-up: an unknown color token, a malformed sort order or a `javascript:`
link stops the service with a list of problems.

### Addresses

| Setting (`articulorium.discovery.*`) | Default | Meaning |
|---|---|---|
| `public-url` | `http://localhost:8000/discover/` | the address people and search engines use; canonical URLs, `hreflang` links, Open Graph URLs and sitemaps are built from it |
| `platform-url`, `transform-url`, `load-balanced` | Consul service names | where this service reads the platform (access checks) and the transform service (IIIF summaries) |
| `platform-public-url`, `transform-public-url` | `../platform/`, `../transform/` | the services as visitors reach them, relative to `public-url`; Solr documents are identified by these public IRIs |
| `solr.base-url`, `solr.collection` | `http://solr:8983/solr`, `articulorium` | the collection the index service writes |
| `access.verify`, `access.anonymous-cache-seconds`, `access.parallelism` | `true`, `60`, `8` | access checks (turn off only when the collection holds nothing but public resources) |
| `iiif.enabled`, `iiif.cache-seconds` | `true`, `300` | IIIF summaries of resource pages (preview image, kinds of media) |

### Identity

Texts take a `value`, `translations` by language tag and/or a `key` of the user interface's
translations:

```yaml
articulorium:
  discovery:
    site:
      name:
        value: Special Collections
        translations: { de: Sondersammlungen, fr: Collections spéciales }
      description:
        key: site.description          # the interface's own text, translated into every locale
      tagline:
        value: Rare books, maps and recordings
      logo-url: /branding/logo.svg     # absolute, or relative to public-url
      favicon-url: /branding/favicon.svg
      default-image-url: /branding/share.png   # shared on social networks when a page has none
      organization-name: Example University Libraries
      organization-url: https://library.example.edu/
      twitter-site: "@examplelib"
```

### Theme

```yaml
    theme:
      color-scheme: system            # light, dark or system (follows the visitor)
      colors:                         # Material 3 system tokens (--mat-sys-<token>), any CSS color
        primary: "#1b5e7a"
        on-primary: "#ffffff"
        surface: "#fbfcfd"
      dark-colors:
        primary: "#8fd0ee"
      typography:
        brand-family: "'Source Serif 4', Georgia, serif"   # headings
        plain-family: "Inter, system-ui, sans-serif"       # body text
        stylesheet-url: https://fonts.example.org/css2?family=Inter   # declares the fonts
        base-size: 16px
      corner-radius: 8                # px; cards, fields, buttons
      density: -1                     # 0 (default) to -4 (compact)
      content-width: 1200px
      stylesheet-url: /branding/discovery.css   # loaded last: overrides anything
      custom-css: |
        .disc-hero { padding-block: 64px; }
```

Color tokens are the Material 3 system colors (`primary`, `on-primary`, `primary-container`,
`secondary`, `tertiary`, `error`, `surface`, `surface-container`, `outline`, ... see
`DiscoverySettingsValidator.COLOR_TOKENS`). `stylesheet-url` and `custom-css` are the extension point
for anything beyond tokens; the built-in components use `disc-*` classes and `--mat-sys-*` tokens.
`custom-css` may not contain `<`.

### Layouts

```yaml
    layout:
      header:
        search: true
        links:
          - label: { value: About, translations: { de: Über uns } }
            url: https://library.example.edu/about
      footer:
        text: { value: "© Example University" }
        links:
          - label: { value: Accessibility }
            url: https://library.example.edu/accessibility
      home:
        sections: [HERO, SEARCH, COLLECTIONS, RECENT]   # any order, any subset
        collections-path: communities                   # whose children the COLLECTIONS section lists
        collections-limit: 12
        recent-limit: 6
      results:
        view: GRID                    # LIST or GRID
        facet-position: END           # START or END (before or after the results)
        thumbnails: true
        page-size: 24
        page-sizes: [12, 24, 48]
        description-length: 280
      item:
        viewer-position: SIDE         # TOP, BOTTOM or SIDE (beside the metadata on wide screens)
        members-limit: 24
        repository-link: false        # link to the resource in the repository's user interface
        fields:                       # metadata of resource pages, in order
          - field: creator
          - field: date
          - field: subject
          - field: license
            link: true
          - field: bibo_doi           # any Solr field; label defaults to the translation fields.<field>
            label: { value: DOI }
    viewer:
      enabled: true
      height: 70vh
      side-bar-open: false
      thumbnail-navigation: far-bottom   # far-bottom, far-right or off
      allow-fullscreen: true
      pdf: true                          # present painted PDFs with the PDF plugin
```

### Search: precedence, facets, filters, sort orders

```yaml
    search:
      fields:                         # what is searched and how hits rank (edismax qf)
        - { field: title, boost: 10, localized: true }   # also title_txt_<visitor's language>
        - { field: creator, boost: 5 }
        - { field: subject, boost: 4, localized: true }
        - { field: description, boost: 2, localized: true }
        - { field: _text_, boost: 1 }
      phrase-fields:                  # whole phrases rank higher (pf)
        - { field: title, boost: 20 }
      boost-queries:                  # these rank higher at equal relevance (bq)
        - 'type_str:"https://articulorium.org/ns/repository#Collection"^2'
      minimum-match: "2<-1 5<80%"
      base-filters:                   # what the discovery layer shows at all
        - 'type_str:("https://articulorium.org/ns/repository#Community" OR "https://articulorium.org/ns/repository#Collection" OR "http://pcdm.org/models#Object")'
        - '-hasParent_str:*\/members'
      facets:
        - id: type
          field: type_str             # exact values: the _default config set's <field>_str copies
          limit: 8
          sort: COUNT                 # COUNT or INDEX
          values:                     # labels of values; keys that are not plain words in brackets
            "[http://purl.org/ontology/bibo/Book]": { key: types.book }
        - id: year
          field: date
          type: YEAR_RANGE
          start: 1800
          gap: 10                     # years per range; end defaults to next year
          expanded: false
      filters:                        # switched on and off by visitors
        - id: digitized
          query: 'hasFile_str:* OR hasMember_str:*'
          label: { value: Digitized only }
      sorts:                          # the first is the default for searches with terms
        - { id: relevance, value: score desc }
        - { id: title, value: title_str asc }
        - { id: newest, value: lastModified desc }
      browse-sort: lastModified desc  # searches without terms
```

The base filters keep pages of books and other nested objects out (the topology service builds
them in `members` containers); remove the second one to list them too. Search terms are read by
Solr's `edismax` parser with field queries turned off (`uf=-*`); facet values are always quoted and
filters and sort orders are chosen by identifier, so visitors cannot address other fields.

Facets on `topology_str` and preview images (`thumbnail`) need the transform service's
`topology` and `thumbnail` field mappings (shipped).

### Search engines

| Setting (`seo.*`) | Default | Meaning |
|---|---|---|
| `robots` | `index, follow` | robots directives of the home page and resource pages |
| `search-robots` | `noindex, follow` | of search result pages (endless parameter combinations; their links are followed) |
| `noindex` | `false` | test and staging deployments: every page says `noindex, nofollow`, robots.txt disallows everything, no sitemap |
| `twitter-card` | `summary_large_image` | Twitter card type of pages with an image |
| `sitemap`, `sitemap-limit` | `true`, `5000` | `/discover/sitemap.xml` |

## Development

```bash
mvn -pl discovery -am verify
java -jar discovery/target/discovery-1.0.0-SNAPSHOT.jar --spring.cloud.consul.enabled=false \
  --articulorium.discovery.load-balanced=false \
  --articulorium.discovery.platform-url=http://localhost:9001/ \
  --articulorium.discovery.transform-url=http://localhost:9002/ \
  --articulorium.discovery.solr.base-url=http://localhost:8983/solr
```

Tests mock Solr, the platform and the transform service with `MockRestServiceServer`; the
controller test starts the application with the shipped settings, so a setting that does not
validate fails the build.
