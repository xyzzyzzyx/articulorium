package com.articulorium.index.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Tests the shipped Solr settings.
 */
class SolrPropertiesTest {

    @Test
    void declaresTheFieldsTheDiscoveryLayerSortsAndFacetsOn() throws IOException {
        final StandardEnvironment environment = new StandardEnvironment();
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
            .forEach(environment.getPropertySources()::addLast);

        final SolrProperties properties = Binder.get(environment).bind("articulorium.solr", SolrProperties.class).get();

        // field names keep their case: Solr field names are case sensitive
        assertThat(properties.getFields()).containsEntry("created", "pdate").containsEntry("lastModified", "pdate")
            .containsEntry("date", "pdates");
    }

}
