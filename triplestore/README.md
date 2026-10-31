# Triplestore service

Mirrors every LDP resource into a SPARQL 1.1 store, one named graph per resource (named by the
resource's IRI), from the `ResourceEvent`s on the resource bus: created and updated resources
replace their graph, deleted ones drop it.

The default store is [QLever](https://github.com/ad-freiburg/qlever) (it replaced Blazegraph, which
is no longer maintained). Graphs are written with the SPARQL 1.1 Graph Store HTTP Protocol
(`PUT`/`DELETE <endpoint>?graph=<iri>`) and every request carries QLever's access token as
`Authorization: Bearer`. docker compose runs QLever as described in [qlever/README.md](qlever/README.md).

| Setting (`articulorum.triplestore.*`) | Default              | Meaning                                   |
|---------------------------------------|----------------------|-------------------------------------------|
| `service-url`                         | `http://qlever:7001` | the store                                 |
| `sparql-path`                         | (root)               | SPARQL query/update endpoint below it     |
| `graph-store-path`                    | (root)               | Graph Store Protocol endpoint below it    |
| `access-token`                        | `${QLEVER_ACCESS_TOKEN}` | sent as `Authorization: Bearer`       |
| `base-uri`                            | (the platform's)     | base graph names are minted under         |

Any store implementing SPARQL 1.1 and the Graph Store Protocol works, for example Apache Jena
Fuseki with `sparql-path: /ds/sparql` and `graph-store-path: /ds/data`. The `qlever` health
indicator pings the store and counts the graphs.
