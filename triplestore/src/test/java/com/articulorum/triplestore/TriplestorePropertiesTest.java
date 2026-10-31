package com.articulorum.triplestore;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorum.triplestore.config.TriplestoreProperties;

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

}
