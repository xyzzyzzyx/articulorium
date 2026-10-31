package com.articulorum.event.health;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings of the Kafka health indicator ({@code articulorum.health.kafka.*}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorum.health.kafka")
public class KafkaHealthProperties {

    /**
     * Comma separated {@code host:port} list of brokers to check. Empty means: use the brokers of
     * the Spring Cloud Stream Kafka binder ({@code spring.cloud.stream.kafka.binder.brokers}).
     */
    private String bootstrapServers;

    /** How long a health check may wait for the cluster to answer. */
    private Duration timeout = Duration.ofSeconds(3);

}
