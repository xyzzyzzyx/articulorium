package com.articulorium.harvest.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import com.articulorium.harvest.classify.AgentClient;
import com.articulorium.harvest.classify.ClassificationStore;
import com.articulorium.harvest.classify.ClassifyingIngestor;
import com.articulorium.harvest.classify.RecordPlacer;
import com.articulorium.harvest.classify.ReviewService;
import com.articulorium.harvest.classify.TopologyClient;
import com.articulorium.harvest.ingest.PlatformAuthorization;
import com.articulorium.harvest.ingest.PlatformClient;
import com.articulorium.harvest.ingest.PlatformIngestor;
import com.articulorium.harvest.ingest.RecordPaths;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * Wires the classification of harvested records by the AI agent, when
 * {@code articulorium.harvest.classification.enabled=true}: the {@link ClassifyingIngestor} replaces
 * the plain ingestor, and the review queue is served. Without it, harvests are not affected by the
 * agent in any way.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnBooleanProperty(name = "articulorium.harvest.classification.enabled")
public class ClassificationConfiguration {

    /**
     * The client of the AI agent.
     *
     * @param builder       the HTTP client builder (load balanced when configured)
     * @param properties    the harvest settings
     * @param authorization the harvester's credentials
     * @return the client
     */
    @Bean
    public AgentClient agentClient(final RestClient.Builder builder, final HarvestProperties properties,
            final PlatformAuthorization authorization) {
        final Duration timeout = properties.getClassification().getTimeout();
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build());
        factory.setReadTimeout(timeout);
        return new AgentClient(builder.clone().requestFactory(factory).build(), properties.getClassification().getAiUrl(),
            authorization);
    }

    /**
     * The client of the topology API.
     *
     * @param builder    the HTTP client builder
     * @param properties the harvest settings
     * @return the client
     */
    @Bean
    public TopologyClient classificationTopologyClient(final RestClient.Builder builder,
            final HarvestProperties properties) {
        return new TopologyClient(builder.clone().build(), properties.getClassification().getTopologyUrl());
    }

    /**
     * The classifications.
     *
     * @return the store
     */
    @Bean
    public ClassificationStore classificationStore() {
        return new ClassificationStore();
    }

    /**
     * Places accepted records.
     *
     * @param store      the classifications
     * @param topologies the topology API
     * @param clock      the clock
     * @return the placer
     */
    @Bean
    public RecordPlacer recordPlacer(final ClassificationStore store, final TopologyClient topologies,
            final Clock clock) {
        return new RecordPlacer(store, topologies, clock);
    }

    /**
     * The ingestor with the agent in the loop; it takes the plain ingestor's place.
     *
     * @param delegate      the plain ingestor
     * @param platform      the platform client
     * @param authorization the harvester's credentials
     * @param paths         where records go
     * @param store         the classifications
     * @param agent         the agent
     * @param placer        places accepted records
     * @param properties    the harvest settings
     * @param clock         the clock
     * @param meters        the meter registry
     * @return the ingestor
     */
    @Bean
    @Primary
    public ClassifyingIngestor classifyingIngestor(final PlatformIngestor delegate, final PlatformClient platform,
            final PlatformAuthorization authorization, final RecordPaths paths, final ClassificationStore store,
            final AgentClient agent, final RecordPlacer placer, final HarvestProperties properties, final Clock clock,
            final MeterRegistry meters) {
        return new ClassifyingIngestor(delegate, platform, authorization, paths, store, agent, placer,
            properties.getClassification(), source -> {
                final HarvestProperties.Source settings = properties.getSources().get(source);
                return settings == null || settings.isClassify();
            }, clock, meters);
    }

    /**
     * The review queue.
     *
     * @param platform   the platform client
     * @param paths      where records go
     * @param store      the classifications
     * @param placer     places accepted records
     * @param ingestor   classifies records
     * @param properties the harvest settings
     * @param clock      the clock
     * @return the service
     */
    @Bean
    public ReviewService reviewService(final PlatformClient platform, final RecordPaths paths,
            final ClassificationStore store, final RecordPlacer placer, final ClassifyingIngestor ingestor,
            final HarvestProperties properties, final Clock clock) {
        return new ReviewService(platform, paths, store, placer, ingestor, properties, clock);
    }

}
