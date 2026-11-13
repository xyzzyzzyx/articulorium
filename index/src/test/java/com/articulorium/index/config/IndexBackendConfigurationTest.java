package com.articulorium.index.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorium.index.backend.DocumentIndex;
import com.articulorium.index.elasticsearch.ElasticsearchIndexClient;
import com.articulorium.index.health.IndexHealthIndicator;
import com.articulorium.index.solr.SolrIndexClient;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.health.registry.DefaultHealthContributorRegistry;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.client.RestClient;

/**
 * Tests the selection of the search index backend.
 */
class IndexBackendConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class);

    @Test
    void solrIsTheBackendUnlessConfigured() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(DocumentIndex.class);
            assertThat(context.getBean(DocumentIndex.class)).isInstanceOf(SolrIndexClient.class);
            assertThat(context.getBean(HealthContributorRegistry.class).getContributor("solr"))
                .isInstanceOf(IndexHealthIndicator.class);
            assertThat(context.getBean(HealthContributorRegistry.class).getContributor("elasticsearch")).isNull();
        });
    }

    @Test
    void solrCanBeSelectedExplicitly() {
        runner.withPropertyValues("articulorium.index.backend=solr").run(context ->
            assertThat(context.getBean(DocumentIndex.class)).isInstanceOf(SolrIndexClient.class));
    }

    @Test
    void elasticsearchCanBeSelected() {
        runner.withPropertyValues("articulorium.index.backend=elasticsearch",
            "articulorium.elasticsearch.base-url=http://search.test:9200", "articulorium.elasticsearch.index=library")
            .run(context -> {
                final DocumentIndex index = context.getBean(DocumentIndex.class);
                assertThat(index).isInstanceOf(ElasticsearchIndexClient.class);
                assertThat(index.location()).containsEntry("baseUrl", "http://search.test:9200")
                    .containsEntry("index", "library");
                assertThat(context.getBean(HealthContributorRegistry.class).getContributor("elasticsearch"))
                    .isInstanceOf(IndexHealthIndicator.class);
                assertThat(context.getBean(HealthContributorRegistry.class).getContributor("solr")).isNull();
            });
    }

    @Test
    void theBackendsNameIsNotCaseSensitive() {
        runner.withPropertyValues("articulorium.index.backend=ELASTICSEARCH").run(context ->
            assertThat(context.getBean(DocumentIndex.class)).isInstanceOf(ElasticsearchIndexClient.class));
    }

    @Test
    void anUnknownBackendFailsTheStartUp() {
        runner.withPropertyValues("articulorium.index.backend=lucene").run(context ->
            assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("lucene"));
    }

    @Test
    void theHealthComponentCanBeDisabled() {
        runner.withPropertyValues("management.health.solr.enabled=false").run(context ->
            assertThat(context.getBean(HealthContributorRegistry.class).getContributor("solr")).isNull());
    }

    @Test
    void worksWithoutHealthSupport() {
        new ApplicationContextRunner().withUserConfiguration(WithoutHealth.class).run(context ->
            assertThat(context).hasSingleBean(DocumentIndex.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({IndexProperties.class, SolrProperties.class, ElasticsearchProperties.class})
    @Import(IndexBackendConfiguration.class)
    static class WithoutHealth {

        @Bean
        RestClient.Builder restClientBuilder() {
            return RestClient.builder();
        }

    }

    @Configuration(proxyBeanMethods = false)
    @Import(WithoutHealth.class)
    static class TestConfiguration {

        @Bean
        HealthContributorRegistry healthContributorRegistry() {
            return new DefaultHealthContributorRegistry();
        }

    }

}
