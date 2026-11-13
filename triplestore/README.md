# Triplestore service

Mirrors every LDP resource into a SPARQL 1.1 store, one named graph per resource (named by the
resource's IRI), from the `ResourceEvent`s on the resource bus: created and updated resources
replace their graph, deleted ones drop it.

## Choosing the store

`articulorium.triplestore.backend` selects the store. It is read when the service starts (not when
it is built, so one native image serves every backend); an unknown value stops the start-up.

| `backend`          | Store                                                              |
|--------------------|--------------------------------------------------------------------|
| `qlever` (default) | [QLever](https://github.com/ad-freiburg/qlever)                    |
| `blazegraph`       | [Blazegraph](https://github.com/blazegraph/database)               |
| `jena`             | [Apache Jena Fuseki](https://jena.apache.org/documentation/fuseki2/) |

Without the setting the service uses QLever exactly as before. `articulorium.triplestore.base-uri`
(the base graph names are minted under, the platform's unless set) applies to every backend.
Switching the backend does not copy graphs: a resource reaches the new store when it is next
modified.

### QLever (default)

Graphs are written with the SPARQL 1.1 Graph Store HTTP Protocol (`PUT`/`DELETE
<endpoint>?graph=<iri>`) and every request carries QLever's access token as `Authorization:
Bearer`. docker compose runs QLever as described in [qlever/README.md](qlever/README.md).

| Setting (`articulorium.triplestore.*`) | Default                  | Meaning                                |
|----------------------------------------|--------------------------|----------------------------------------|
| `service-url`                          | `http://qlever:7001`     | the store                              |
| `sparql-path`                          | (root)                   | SPARQL query/update endpoint below it  |
| `graph-store-path`                     | (root)                   | Graph Store Protocol endpoint below it |
| `access-token`                         | `${QLEVER_ACCESS_TOKEN}` | sent as `Authorization: Bearer`        |

### Blazegraph

```yaml
articulorium:
  triplestore:
    backend: blazegraph
    blazegraph:
      service-url: http://blazegraph:8080/bigdata
      namespace: articulorium
```

| Setting (`articulorium.triplestore.blazegraph.*`) | Default                          | Meaning |
|---------------------------------------------------|----------------------------------|---------|
| `service-url`                                     | `http://blazegraph:8080/bigdata` | the Blazegraph web application: `.../bigdata` in the Docker images, `http://localhost:9999/blazegraph` for `blazegraph.jar` |
| `namespace`                                       | `articulorium`                   | the namespace graphs are written to (`<service-url>/namespace/<namespace>/sparql`) |
| `username`, `password`                            | (none)                           | HTTP Basic authentication, when Blazegraph sits behind it |

* Named graphs need a namespace in quads mode. The service creates the namespace that way when it
  does not exist (retrying while Blazegraph starts). An existing namespace is used as it is; when
  it is not in quads mode (the default `kb` namespace is not) the service logs an error and graphs
  cannot be written: configure another namespace.
* Blazegraph does not implement the Graph Store HTTP Protocol. A graph is replaced with a `DROP
  SILENT GRAPH` update followed by a `POST` of its content with the graph as `context-uri`. The two
  requests are not one transaction: when the second fails the graph stays empty until the resource
  changes again. Characters that cannot appear in an IRI are percent-encoded in graph names.
* Blazegraph is no longer maintained; the backend exists for deployments that already run it.

`docker compose -f docker-compose.yml -f docker-compose.blazegraph.yml up --build` runs the stack
with the `lyrasis/blazegraph` image (workbench: <http://localhost:8889/bigdata/>).

### Apache Jena Fuseki

```yaml
articulorium:
  triplestore:
    backend: jena
    jena:
      service-url: http://fuseki:3030
      dataset: articulorium
```

| Setting (`articulorium.triplestore.jena.*`) | Default              | Meaning |
|---------------------------------------------|----------------------|---------|
| `service-url`                               | `http://fuseki:3030` | the Fuseki server |
| `dataset`                                   | `articulorium`       | the dataset graphs are written to |
| `username`, `password`                      | (none)               | HTTP Basic authentication, when the dataset requires it |

* The dataset must exist and allow updates (the service does not create it: Fuseki's administration
  is often not reachable or not enabled). A persistent (TDB2) dataset keeps the graphs over restarts.
* The dataset's `sparql` (query), `update` and `data` (Graph Store HTTP Protocol) services are
  used, the names Fuseki gives them by default: `<service-url>/<dataset>/sparql`, `/update`, `/data`.

`docker compose -f docker-compose.yml -f docker-compose.jena.yml up --build` runs the stack with
the `stain/jena-fuseki` image, which creates the dataset on first start (<http://localhost:3030/>,
user `admin`, password `FUSEKI_ADMIN_PASSWORD`, default `articulorium-fuseki`).

## Health

The health component is named after the backend (`qlever`, `blazegraph` or `jena`). It checks that
the store answers (QLever: `GET /ping`; the others: a query), counts the graphs and reports the
SPARQL endpoint. Disable it with `management.health.<backend>.enabled=false`.

## Adding a store

Implement `com.articulorium.triplestore.backend.TripleStore` (stores speaking the standard
protocols extend `SparqlStoreClient` and only name their endpoints and authentication), add a value
to `TriplestoreProperties.Backend` and a case to `TriplestoreBackendConfiguration`.
