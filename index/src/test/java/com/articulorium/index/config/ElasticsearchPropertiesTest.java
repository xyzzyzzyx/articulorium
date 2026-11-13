package com.articulorium.index.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Tests the shipped index and Elasticsearch settings.
 */
class ElasticsearchPropertiesTest {

    private static Binder shipped() throws IOException {
        final StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
            .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment);
    }

    @Test
    void solrIsTheShippedBackend() throws IOException {
        assertThat(shipped().bind("articulorium.index", IndexProperties.class).get().getBackend())
            .isEqualTo(IndexProperties.Backend.SOLR);
        assertThat(new IndexProperties().getBackend()).isEqualTo(IndexProperties.Backend.SOLR);
    }

    @Test
    void declaresTheFieldsTheDiscoveryLayerSortsAndFacetsOnAsDates() throws IOException {
        final ElasticsearchProperties properties = shipped()
            .bind("articulorium.elasticsearch", ElasticsearchProperties.class).get();

        // field names keep their case: Elasticsearch field names are case sensitive
        assertThat(properties.getFields()).containsEntry("created", "date").containsEntry("lastModified", "date")
            .containsEntry("date", "date");
        assertThat(properties.getBaseUrl()).isEqualTo("http://elasticsearch:9200");
        assertThat(properties.getApiKey()).isNull();
        assertThat(properties.getUsername()).isNull();
    }

}
