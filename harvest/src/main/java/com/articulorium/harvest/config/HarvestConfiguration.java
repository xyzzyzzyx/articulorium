package com.articulorium.harvest.config;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

import com.articulorium.harvest.core.HarvestPolicy;
import com.articulorium.harvest.core.Harvester;
import com.articulorium.harvest.ingest.PlatformAuthorization;
import com.articulorium.harvest.ingest.PlatformCheckpointStore;
import com.articulorium.harvest.ingest.PlatformClient;
import com.articulorium.harvest.ingest.PlatformIngestor;
import com.articulorium.harvest.ingest.RecordPaths;
import com.articulorium.harvest.source.SourceRestClients;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.cloud.client.loadbalancer.LoadBalanced;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

/**
 * Wires the harvest workflow: sources share one plain HTTP client builder, the platform is reached
 * through the load balancer (unless {@code articulorium.harvest.load-balanced=false}), records go to
 * the {@link PlatformIngestor} and checkpoints to the {@link PlatformCheckpointStore}.
 */
@Configuration(proxyBeanMethods = false)
public class HarvestConfiguration {

    /**
     * A builder of clients that resolve Consul service names such as {@code http://articulorium-platform}.
     *
     * @return the load-balanced builder
     */
    @Bean
    @LoadBalanced
    @ConditionalOnBooleanProperty(name = "articulorium.harvest.load-balanced", matchIfMissing = true)
    public RestClient.Builder loadBalancedRestClientBuilder() {
        return RestClient.builder();
    }

    /**
     * The clock.
     *
     * @return the system clock in UTC
     */
    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    /**
     * The HTTP client builder of the sources: outside the service mesh, with timeouts and the
     * harvester's {@code User-Agent}.
     *
     * @param properties the harvest settings
     * @return the clients
     */
    @Bean
    public SourceRestClients sourceRestClients(final HarvestProperties properties) {
        final HarvestProperties.Http http = properties.getHttp();
        return new SourceRestClients(RestClient.builder()
            .requestFactory(requestFactory(http.getConnectTimeout(), http.getReadTimeout()))
            .defaultHeader(HttpHeaders.USER_AGENT, http.getUserAgent()));
    }

    /**
     * The harvester's credentials towards the platform.
     *
     * @param properties the harvest settings
     * @param clock      the clock
     * @return the credentials
     */
    @Bean
    public PlatformAuthorization platformAuthorization(final HarvestProperties properties, final Clock clock) {
        final RestClient tokenClient = RestClient.builder()
            .requestFactory(requestFactory(Duration.ofSeconds(10), Duration.ofSeconds(30)))
            .build();
        return new PlatformAuthorization(properties.getPlatformAuth(), tokenClient, clock);
    }

    /**
     * The platform client.
     *
     * @param builder       the platform client builder (load balanced when configured)
     * @param properties    the harvest settings
     * @param authorization the harvester's credentials
     * @return the client
     */
    @Bean
    public PlatformClient platformClient(final RestClient.Builder builder, final HarvestProperties properties,
            final PlatformAuthorization authorization) {
        return new PlatformClient(builder.clone().build(), properties.getPlatformUrl(), authorization);
    }

    /**
     * Where records go.
     *
     * @param properties the harvest settings
     * @return the paths
     */
    @Bean
    public RecordPaths recordPaths(final HarvestProperties properties) {
        return new RecordPaths(properties.getContainer());
    }

    /**
     * The ingestor.
     *
     * @param platform the platform client
     * @param paths    the paths
     * @param clock    the clock
     * @return the ingestor
     */
    @Bean
    public PlatformIngestor platformIngestor(final PlatformClient platform, final RecordPaths paths, final Clock clock) {
        return new PlatformIngestor(platform, paths, clock);
    }

    /**
     * The checkpoint store.
     *
     * @param platform the platform client
     * @param paths    the paths
     * @return the store
     */
    @Bean
    public PlatformCheckpointStore platformCheckpointStore(final PlatformClient platform, final RecordPaths paths) {
        return new PlatformCheckpointStore(platform, paths);
    }

    /**
     * The harvest workflow.
     *
     * @param properties the harvest settings
     * @param clock      the clock
     * @return the harvester
     */
    @Bean
    public Harvester harvester(final HarvestProperties properties, final Clock clock) {
        final HarvestProperties.Policy policy = properties.getPolicy();
        return new Harvester(new HarvestPolicy(policy.getMaxAttempts(), policy.getBackoff(), policy.getMaxBackoff(),
            policy.getMaxFailures(), policy.getMaxReportedFailures(), policy.getOverlap()), clock, Harvester.Sleeper.THREAD);
    }

    /**
     * The scheduler of scheduled harvests (it only starts them; they run on the harvest executor).
     *
     * @return the scheduler
     */
    @Bean
    public ThreadPoolTaskScheduler harvestScheduler() {
        final ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("harvest-schedule-");
        scheduler.setPoolSize(1);
        return scheduler;
    }

    private static JdkClientHttpRequestFactory requestFactory(final Duration connect, final Duration read) {
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder()
            .connectTimeout(connect)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build());
        factory.setReadTimeout(read);
        return factory;
    }

}
