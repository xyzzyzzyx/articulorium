package com.articulorum.event.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.DescribeClusterOptions;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.internals.KafkaFutureImpl;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Unit tests for {@link KafkaHealthIndicator} and {@link KafkaHealthConfiguration}.
 */
class KafkaHealthIndicatorTest {

    private static final Duration TIMEOUT = Duration.ofMillis(200);

    private final Admin admin = mock(Admin.class);

    private final DescribeClusterResult cluster = mock(DescribeClusterResult.class);

    @Test
    void upWhenTheClusterAnswers() {
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
        when(cluster.clusterId()).thenReturn(KafkaFuture.completedFuture("cluster-1"));
        when(cluster.nodes()).thenReturn(KafkaFuture.completedFuture(List.of(new Node(1, "kafka", 9092))));

        final Health health = new KafkaHealthIndicator(admin, "kafka:9092", TIMEOUT).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
            .containsEntry("clusterId", "cluster-1")
            .containsEntry("brokers", 1)
            .containsEntry("bootstrapServers", "kafka:9092");
    }

    @Test
    void downWhenTheClusterHasNoBrokers() {
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
        when(cluster.clusterId()).thenReturn(KafkaFuture.completedFuture(null));
        when(cluster.nodes()).thenReturn(KafkaFuture.completedFuture(List.of()));

        final Health health = new KafkaHealthIndicator(admin, "kafka:9092", TIMEOUT).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("clusterId", "unknown").containsEntry("brokers", 0);
    }

    @Test
    void downWhenTheClusterDoesNotAnswer() {
        final KafkaFutureImpl<String> failed = new KafkaFutureImpl<>();
        failed.completeExceptionally(new TimeoutException("no answer"));
        when(admin.describeCluster(any(DescribeClusterOptions.class))).thenReturn(cluster);
        when(cluster.clusterId()).thenReturn(failed);

        final Health health = new KafkaHealthIndicator(admin, "kafka:9092", TIMEOUT).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("error");
    }

    @Test
    void bootstrapServersPreferIndicatorThenBinderThenDefault() {
        final KafkaHealthProperties properties = new KafkaHealthProperties();
        assertThat(KafkaHealthConfiguration.bootstrapServers(properties, ""))
            .isEqualTo(KafkaHealthConfiguration.DEFAULT_BROKERS);
        assertThat(KafkaHealthConfiguration.bootstrapServers(properties, "kafka:9092")).isEqualTo("kafka:9092");
        properties.setBootstrapServers("other:9092");
        assertThat(KafkaHealthConfiguration.bootstrapServers(properties, "kafka:9092")).isEqualTo("other:9092");
    }

}
