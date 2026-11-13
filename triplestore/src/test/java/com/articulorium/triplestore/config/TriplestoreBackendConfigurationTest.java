package com.articulorium.triplestore.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorium.triplestore.backend.TripleStore;
import com.articulorium.triplestore.blazegraph.BlazegraphStoreClient;
import com.articulorium.triplestore.health.TriplestoreHealthIndicator;
import com.articulorium.triplestore.jena.JenaStoreClient;
import com.articulorium.triplestore.qlever.QleverStoreClient;

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
 * Tests the selection of the SPARQL store.
 */
class TriplestoreBackendConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withUserConfiguration(TestConfiguration.class);

    @Test
    void qleverIsTheBackendUnlessConfigured() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(TripleStore.class);
            final TripleStore store = context.getBean(TripleStore.class);
            assertThat(store).isInstanceOf(QleverStoreClient.class);
            assertThat(store.location()).containsEntry("endpoint", "http://qlever:7001/");
            final HealthContributorRegistry health = context.getBean(HealthContributorRegistry.class);
            assertThat(health.getContributor("qlever")).isInstanceOf(TriplestoreHealthIndicator.class);
            assertThat(health.getContributor("blazegraph")).isNull();
            assertThat(health.getContributor("jena")).isNull();
        });
    }

    @Test
    void qleverCanBeSelectedExplicitlyAndKeepsItsSettings() {
        runner.withPropertyValues("articulorium.triplestore.backend=qlever",
            "articulorium.triplestore.service-url=http://sparql.test:7019", "articulorium.triplestore.sparql-path=/api")
            .run(context -> {
                final TripleStore store = context.getBean(TripleStore.class);
                assertThat(store).isInstanceOf(QleverStoreClient.class);
                assertThat(store.location()).containsEntry("endpoint", "http://sparql.test:7019/api");
            });
    }

    @Test
    void blazegraphCanBeSelected() {
        runner.withPropertyValues("articulorium.triplestore.backend=blazegraph",
            "articulorium.triplestore.blazegraph.service-url=http://graphs.test:9999/blazegraph",
            "articulorium.triplestore.blazegraph.namespace=library")
            .run(context -> {
                final TripleStore store = context.getBean(TripleStore.class);
                assertThat(store).isInstanceOf(BlazegraphStoreClient.class);
                assertThat(store.location())
                    .containsEntry("endpoint", "http://graphs.test:9999/blazegraph/namespace/library/sparql")
                    .containsEntry("namespace", "library");
                final HealthContributorRegistry health = context.getBean(HealthContributorRegistry.class);
                assertThat(health.getContributor("blazegraph")).isInstanceOf(TriplestoreHealthIndicator.class);
                assertThat(health.getContributor("qlever")).isNull();
            });
    }

    @Test
    void jenaCanBeSelected() {
        runner.withPropertyValues("articulorium.triplestore.backend=jena",
            "articulorium.triplestore.jena.service-url=http://fuseki.test:3030",
            "articulorium.triplestore.jena.dataset=library")
            .run(context -> {
                final TripleStore store = context.getBean(TripleStore.class);
                assertThat(store).isInstanceOf(JenaStoreClient.class);
                assertThat(store.location()).containsEntry("endpoint", "http://fuseki.test:3030/library/sparql")
                    .containsEntry("dataset", "library");
                final HealthContributorRegistry health = context.getBean(HealthContributorRegistry.class);
                assertThat(health.getContributor("jena")).isInstanceOf(TriplestoreHealthIndicator.class);
                assertThat(health.getContributor("qlever")).isNull();
            });
    }

    @Test
    void theOtherBackendsDefaultToTheirComposeServices() {
        runner.withPropertyValues("articulorium.triplestore.backend=blazegraph").run(context ->
            assertThat(context.getBean(TripleStore.class).location())
                .containsEntry("endpoint", "http://blazegraph:8080/bigdata/namespace/articulorium/sparql"));
        runner.withPropertyValues("articulorium.triplestore.backend=jena").run(context ->
            assertThat(context.getBean(TripleStore.class).location())
                .containsEntry("endpoint", "http://fuseki:3030/articulorium/sparql"));
    }

    @Test
    void theBackendsNameIsNotCaseSensitive() {
        runner.withPropertyValues("articulorium.triplestore.backend=JENA").run(context ->
            assertThat(context.getBean(TripleStore.class)).isInstanceOf(JenaStoreClient.class));
    }

    @Test
    void anUnknownBackendFailsTheStartUp() {
        runner.withPropertyValues("articulorium.triplestore.backend=virtuoso").run(context ->
            assertThat(context).hasFailed().getFailure().rootCause().hasMessageContaining("virtuoso"));
    }

    @Test
    void aBackendWithoutItsRequiredSettingsFailsTheStartUp() {
        runner.withPropertyValues("articulorium.triplestore.backend=jena", "articulorium.triplestore.jena.dataset=")
            .run(context -> assertThat(context).hasFailed().getFailure().rootCause()
                .hasMessageContaining("articulorium.triplestore.jena.dataset"));
    }

    @Test
    void theHealthComponentCanBeDisabled() {
        runner.withPropertyValues("management.health.qlever.enabled=false").run(context ->
            assertThat(context.getBean(HealthContributorRegistry.class).getContributor("qlever")).isNull());
        runner.withPropertyValues("articulorium.triplestore.backend=jena", "management.health.jena.enabled=false")
            .run(context ->
                assertThat(context.getBean(HealthContributorRegistry.class).getContributor("jena")).isNull());
    }

    @Test
    void worksWithoutHealthSupport() {
        new ApplicationContextRunner().withUserConfiguration(WithoutHealth.class).run(context ->
            assertThat(context).hasSingleBean(TripleStore.class));
    }

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(TriplestoreProperties.class)
    @Import(TriplestoreBackendConfiguration.class)
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
