package com.articulorum.triplestore;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorum.triplestore.config.TriplestoreProperties;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TriplestoreProperties}.
 */
class TriplestorePropertiesTest {

    @Test
    void derivesEndpointsFromTheServiceUrl() {
        final TriplestoreProperties properties = new TriplestoreProperties();
        properties.setServiceUrl("http://blazegraph:8080/bigdata/");
        properties.setNamespace("kb");
        assertThat(properties.sparqlEndpoint()).isEqualTo("http://blazegraph:8080/bigdata/namespace/kb/sparql");
        assertThat(properties.namespacesEndpoint()).isEqualTo("http://blazegraph:8080/bigdata/namespace");
    }

}
