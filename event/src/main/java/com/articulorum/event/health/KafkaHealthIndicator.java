package com.articulorum.event.health;

import java.time.Duration;
import java.util.Collection;
import java.util.concurrent.TimeUnit;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.Node;
import org.springframework.boot.health.contributor.AbstractHealthIndicator;
import org.springframework.boot.health.contributor.Health;

/**
 * Reports whether the Kafka cluster carrying the Spring Cloud Bus is reachable.
 *
 * <p>The check asks the cluster for its id, controller and broker list through the Kafka admin
 * API. The indicator is {@code UP} when the cluster answers within the configured timeout and has
 * at least one broker, and {@code DOWN} otherwise. Details report the bootstrap servers, cluster
 * id and number of brokers.</p>
 */
public class KafkaHealthIndicator extends AbstractHealthIndicator {

    private final Admin admin;

    private final String bootstrapServers;

    private final Duration timeout;

    /**
     * Creates the indicator.
     *
     * @param admin            the admin client used for the checks; it is owned by the caller
     * @param bootstrapServers the brokers the admin client connects to, for the details
     * @param timeout          how long a check may wait for the cluster
     */
    public KafkaHealthIndicator(final Admin admin, final String bootstrapServers, final Duration timeout) {
        super("Kafka health check failed");
        this.admin = admin;
        this.bootstrapServers = bootstrapServers;
        this.timeout = timeout;
    }

    @Override
    protected void doHealthCheck(final Health.Builder builder) throws Exception {
        final long millis = timeout.toMillis();
        final DescribeClusterResult cluster = admin.describeCluster(new DescribeClusterOptions().timeoutMs((int) millis));
        final String clusterId = cluster.clusterId().get(millis, TimeUnit.MILLISECONDS);
        final Collection<Node> nodes = cluster.nodes().get(millis, TimeUnit.MILLISECONDS);
        builder.withDetail("bootstrapServers", bootstrapServers)
            .withDetail("clusterId", clusterId == null ? "unknown" : clusterId)
            .withDetail("brokers", nodes.size());
        if (nodes.isEmpty()) {
            builder.down().withDetail("error", "The cluster reports no brokers");
        } else {
            builder.up();
        }
    }

}
