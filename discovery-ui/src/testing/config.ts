import { DiscoveryConfig } from '../app/core/config/discovery-config';

/** A deployment's configuration for tests: four locales, the shipped facets and fields. */
export function testConfig(): DiscoveryConfig {
  return structuredClone(CONFIG);
}

const CONFIG: DiscoveryConfig = {
  "site": {
    "name": {
      "value": "Articulorium"
    },
    "description": {
      "key": "site.description"
    },
    "twitterSite": "@articulorium"
  },
  "theme": {
    "colorScheme": "light",
    "colors": {
      "primary": "#1b5e7a"
    },
    "darkColors": {},
    "typography": {
      "baseSize": "16px"
    },
    "cornerRadius": 8,
    "density": 0,
    "contentWidth": "1200px"
  },
  "layout": {
    "header": {
      "search": true,
      "links": [
        {
          "label": {
            "value": "About",
            "translations": {
              "de": "Über uns"
            }
          },
          "url": "https://example.org/about"
        }
      ]
    },
    "footer": {
      "text": {
        "value": "© Example Library"
      },
      "links": [
        {
          "label": {
            "value": "Repository"
          },
          "url": "/ui/"
        }
      ]
    },
    "home": {
      "sections": [
        "HERO",
        "SEARCH",
        "COLLECTIONS",
        "RECENT"
      ],
      "collectionsPath": "communities",
      "collectionsLimit": 12,
      "recentLimit": 6
    },
    "results": {
      "view": "LIST",
      "facetPosition": "START",
      "thumbnails": true,
      "pageSize": 20,
      "pageSizes": [
        10,
        20,
        50
      ],
      "descriptionLength": 280
    },
    "item": {
      "viewerPosition": "TOP",
      "fields": [
        {
          "field": "creator",
          "link": false
        },
        {
          "field": "contributor",
          "link": false
        },
        {
          "field": "date",
          "link": false
        },
        {
          "field": "publisher",
          "link": false
        },
        {
          "field": "subject",
          "link": false
        },
        {
          "field": "type",
          "link": true
        },
        {
          "field": "language",
          "link": false
        },
        {
          "field": "format",
          "link": false
        },
        {
          "field": "extent",
          "link": false
        },
        {
          "field": "identifier",
          "link": false
        },
        {
          "field": "rights",
          "link": false
        },
        {
          "field": "license",
          "link": true
        },
        {
          "field": "source",
          "link": false
        },
        {
          "field": "isPartOf",
          "link": false
        },
        {
          "field": "bibliographicCitation",
          "link": false
        }
      ],
      "membersLimit": 24
    },
    "collections": {
      "enabled": true,
      "pageSize": 50,
      "sort": "title"
    }
  },
  "viewer": {
    "enabled": true,
    "height": "70vh",
    "sideBarOpen": false,
    "thumbnailNavigation": "far-bottom",
    "allowFullscreen": true,
    "pdf": true
  },
  "search": {
    "facets": [
      {
        "id": "type",
        "type": "TERMS",
        "limit": 8,
        "expanded": true,
        "values": {
          "http://pcdm.org/models#Object": {
            "key": "kinds.object"
          },
          "http://purl.org/ontology/bibo/Book": {
            "key": "types.book"
          }
        }
      },
      {
        "id": "topology",
        "type": "TERMS",
        "limit": 10,
        "expanded": true,
        "values": {
          "book": {
            "key": "topologies.book"
          }
        }
      },
      {
        "id": "creator",
        "type": "TERMS",
        "limit": 10,
        "expanded": true,
        "values": {}
      },
      {
        "id": "subject",
        "type": "TERMS",
        "limit": 10,
        "expanded": true,
        "values": {}
      },
      {
        "id": "language",
        "type": "TERMS",
        "limit": 10,
        "expanded": true,
        "values": {}
      },
      {
        "id": "year",
        "type": "YEAR_RANGE",
        "limit": 10,
        "expanded": false,
        "values": {}
      }
    ],
    "filters": [
      {
        "id": "digitized"
      }
    ],
    "sorts": [
      {
        "id": "relevance"
      },
      {
        "id": "title"
      },
      {
        "id": "newest"
      },
      {
        "id": "date"
      }
    ],
    "defaultSort": "relevance"
  },
  "seo": {
    "robots": "index, follow",
    "searchRobots": "noindex, follow",
    "noindex": false,
    "twitterCard": "summary_large_image",
    "sitemap": true
  },
  "locales": {
    "defaultLocale": "en",
    "available": [
      {
        "tag": "en",
        "name": "English",
        "direction": "ltr"
      },
      {
        "tag": "ar",
        "name": "العربية",
        "direction": "rtl"
      },
      {
        "tag": "de",
        "name": "Deutsch",
        "direction": "ltr"
      },
      {
        "tag": "pt-BR",
        "name": "Português (Brasil)",
        "direction": "ltr"
      }
    ]
  },
  "urls": {
    "publicUrl": "http://localhost:8000/discover/",
    "platform": "http://localhost:8000/platform/",
    "iiif": "http://localhost:8000/transform/iiif/"
  }
};
