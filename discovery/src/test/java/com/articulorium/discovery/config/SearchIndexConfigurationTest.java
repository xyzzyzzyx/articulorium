package com.articulorium.discovery.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.Map;

import com.articulorium.discovery.elasticsearch.ElasticsearchSearchIndex;
import com.articulorium.discovery.solr.SolrSearchIndex;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

/**
 * Tests the selection of the search index backend.
 */
class SearchIndexConfigurationTest {

    private static DiscoveryProperties bind(final Map<String, Object> overrides) throws IOException {
        final StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addLast(new MapPropertySource("overrides", overrides));
        new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
            .forEach(environment.getPropertySources()::addLast);
        return Binder.get(environment).bind("articulorium.discovery", DiscoveryProperties.class).get();
    }

    @Test
    void solrIsTheBackendUnlessConfigured() throws IOException {
        assertThat(new DiscoveryProperties().getIndex().getBackend()).isEqualTo(DiscoveryProperties.Backend.SOLR);
        assertThat(bind(Map.of()).getIndex().getBackend()).isEqualTo(DiscoveryProperties.Backend.SOLR);
        assertThat(new SearchIndexConfiguration().searchIndex(new DiscoveryProperties())).isInstanceOf(SolrSearchIndex.class);
    }

    @Test
    void elasticsearchCanBeSelected() throws IOException {
        final DiscoveryProperties properties = bind(Map.of("articulorium.discovery.index.backend", "elasticsearch",
            "articulorium.discovery.elasticsearch.base-url", "https://search.example.org:9243",
            "articulorium.discovery.elasticsearch.api-key", "a2V5"));

        assertThat(properties.getElasticsearch().getBaseUrl()).isEqualTo("https://search.example.org:9243");
        assertThat(properties.getElasticsearch().getIndex()).isEqualTo("articulorium");
        assertThat(properties.getElasticsearch().getApiKey()).isEqualTo("a2V5");
        assertThat(new SearchIndexConfiguration().searchIndex(properties)).isInstanceOf(ElasticsearchSearchIndex.class);
    }

    @Test
    void anUnknownBackendIsRefused() {
        assertThatThrownBy(() -> bind(Map.of("articulorium.discovery.index.backend", "lucene")))
            .isInstanceOf(BindException.class);
    }

}
