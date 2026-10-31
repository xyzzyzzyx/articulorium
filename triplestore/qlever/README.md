# QLever for Articulorum

`docker compose` runs [QLever](https://github.com/ad-freiburg/qlever) (image
`adfreiburg/qlever`) as the SPARQL store the `triplestore` service mirrors resources into.

* [`entrypoint.sh`](entrypoint.sh) builds an index from [`seed.nq`](seed.nq) on first start (QLever
  serves queries from a prebuilt index) and then starts the server with an access token and
  `--persist-updates`, so the graphs written at run time survive restarts. The index lives in the
  `qlever-data` volume.
* Queries, SPARQL updates and the Graph Store HTTP Protocol are all served at the root:
  <http://localhost:7001/?query=SELECT%20*%20WHERE%20%7B%20GRAPH%20%3Fg%20%7B%20%3Fs%20%3Fp%20%3Fo%20%7D%20%7D%20LIMIT%2010>.
* Changes need the access token (`QLEVER_ACCESS_TOKEN`, default `articulorum-qlever`), passed as
  `Authorization: Bearer <token>`. The triplestore service reads the same variable.

The seed triple lives in the graph `urn:articulorum:system`, so the triplestore health check's graph
count is one higher than the number of mirrored resources.

To start over, remove the `articulorum_qlever-data` volume; the index is rebuilt on the next start
and graphs are written again as resources change.
