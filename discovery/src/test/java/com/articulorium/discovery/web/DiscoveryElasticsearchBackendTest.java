package com.articulorium.discovery.web;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorium.discovery.elasticsearch.ElasticsearchSearchIndex;
import com.articulorium.discovery.index.SearchIndex;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * Tests that the full application context starts with Elasticsearch as its search index.
 */
// Consul is turned off before the configuration data (spring.config.import) is resolved
@SpringBootTest(properties = {"spring.cloud.consul.enabled=false", "spring.cloud.consul.config.enabled=false",
    "articulorium.discovery.index.backend=elasticsearch",
    "articulorium.discovery.elasticsearch.base-url=http://elasticsearch.test:9200"})
@ActiveProfiles("test")
class DiscoveryElasticsearchBackendTest {

    @Autowired
    private SearchIndex index;

    @Test
    void searchesElasticsearch() {
        assertThat(index).isInstanceOf(ElasticsearchSearchIndex.class);
    }

}
