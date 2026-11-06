package com.articulorium.oai.catalog;

/**
 * A community or collection exposed as an OAI-PMH set.
 *
 * @param path the resource path below the LDP root
 * @param spec its setSpec
 */
public record CatalogSet(String path, String spec) {
}
