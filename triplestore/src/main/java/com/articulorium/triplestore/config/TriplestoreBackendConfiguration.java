package com.articulorium.triplestore.config;

import com.articulorium.triplestore.backend.TripleStore;
import com.articulorium.triplestore.blazegraph.BlazegraphStoreClient;
import com.articulorium.triplestore.health.TriplestoreHealthIndicator;
import com.articulorium.triplestore.jena.JenaStoreClient;
import com.articulorium.triplestore.qlever.QleverStoreClient;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.health.registry.HealthContributorRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Selects the SPARQL store ({@code articulorium.triplestore.backend}): QLever unless configured.
 *
 * <p>The backend is chosen when the service starts, not with bean conditions: conditions are
 * evaluated when a GraalVM native image is built, which would fix the backend at build time.</p>
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
public class TriplestoreBackendConfiguration {

    /**
     * The SPARQL store of the configured backend.
     *
     * @param properties store settings
     * @param builder    the auto-configured REST client builder
     * @return the store
     */
    @Bean
    public TripleStore tripleStore(final TriplestoreProperties properties, final RestClient.Builder builder) {
        final TripleStore store = switch (properties.getBackend()) {
            case QLEVER -> new QleverStoreClient(builder, properties);
            case BLAZEGRAPH -> new BlazegraphStoreClient(builder, properties.getBlazegraph());
            case JENA -> new JenaStoreClient(builder, properties.getJena());
        };
        log.info("Mirroring resources into {}", store.target());
        return store;
    }

    /**
     * Registers the health component of the backend under its name ({@code qlever},
     * {@code blazegraph} or {@code jena}); disable it with
     * {@code management.health.<name>.enabled=false}.
     *
     * @param registry    the health contributors, absent without the actuator's health support
     * @param store       the store
     * @param environment the environment
     * @return the registration, run once every bean exists
     */
    @Bean
    public SmartInitializingSingleton triplestoreHealthRegistration(
            final ObjectProvider<HealthContributorRegistry> registry, final TripleStore store,
            final Environment environment) {
        return () -> {
            if (environment.getProperty("management.health." + store.name() + ".enabled", Boolean.class, true)) {
                registry.ifAvailable(contributors ->
                    contributors.registerContributor(store.name(), new TriplestoreHealthIndicator(store)));
            }
        };
    }

}
