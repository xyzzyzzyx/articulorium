package com.articulorium.triplestore;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorium.triplestore.config.TriplestoreProperties;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TriplestoreProperties}.
 */
class TriplestorePropertiesTest {

    @Test
    void qleverServesEverythingAtTheRoot() {
        final TriplestoreProperties properties = new TriplestoreProperties();
        assertThat(properties.sparqlEndpoint()).isEqualTo("http://qlever:7001/");
        assertThat(properties.graphStoreEndpoint()).isEqualTo("http://qlever:7001/");
    }

    @Test
    void derivesEndpointsFromConfiguredPaths() {
        final TriplestoreProperties properties = new TriplestoreProperties();
        properties.setServiceUrl("http://fuseki:3030/");
        properties.setSparqlPath("ds/sparql");
        properties.setGraphStorePath("/ds/data");
        assertThat(properties.sparqlEndpoint()).isEqualTo("http://fuseki:3030/ds/sparql");
        assertThat(properties.graphStoreEndpoint()).isEqualTo("http://fuseki:3030/ds/data");
    }

    @Test
    void qleverIsTheDefaultBackend() {
        assertThat(new TriplestoreProperties().getBackend()).isEqualTo(TriplestoreProperties.Backend.QLEVER);
    }

    @Test
    void derivesTheBlazegraphEndpointsFromTheNamespace() {
        final TriplestoreProperties.Blazegraph blazegraph = new TriplestoreProperties().getBlazegraph();
        assertThat(blazegraph.sparqlEndpoint()).isEqualTo("http://blazegraph:8080/bigdata/namespace/articulorium/sparql");
        blazegraph.setServiceUrl("http://localhost:9999/blazegraph/");
        blazegraph.setNamespace("kb");
        assertThat(blazegraph.sparqlEndpoint()).isEqualTo("http://localhost:9999/blazegraph/namespace/kb/sparql");
        assertThat(blazegraph.namespacesEndpoint()).isEqualTo("http://localhost:9999/blazegraph/namespace");
        assertThat(blazegraph.namespacePropertiesEndpoint())
            .isEqualTo("http://localhost:9999/blazegraph/namespace/kb/properties");
    }

    @Test
    void derivesTheFusekiServicesFromTheDataset() {
        final TriplestoreProperties.Jena jena = new TriplestoreProperties().getJena();
        assertThat(jena.queryEndpoint()).isEqualTo("http://fuseki:3030/articulorium/sparql");
        jena.setServiceUrl("https://fuseki.example.org/");
        jena.setDataset("/ds/");
        assertThat(jena.datasetUrl()).isEqualTo("https://fuseki.example.org/ds");
        assertThat(jena.queryEndpoint()).isEqualTo("https://fuseki.example.org/ds/sparql");
        assertThat(jena.updateEndpoint()).isEqualTo("https://fuseki.example.org/ds/update");
        assertThat(jena.graphStoreEndpoint()).isEqualTo("https://fuseki.example.org/ds/data");
    }

}
