package com.articulorum.event.health;

import java.util.Map;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Registers the {@code kafka} health indicator for services that take part in the bus.
 *
 * <p>The indicator is skipped when the bus is disabled ({@code spring.cloud.bus.enabled=false},
 * as in tests and stand-alone runs) or when {@code management.health.kafka.enabled=false}.</p>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(Admin.class)
@ConditionalOnProperty(name = { "spring.cloud.bus.enabled", "management.health.kafka.enabled" },
    havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(KafkaHealthProperties.class)
public class KafkaHealthConfiguration {

    /** Brokers used when neither the indicator nor the binder configures any. */
    static final String DEFAULT_BROKERS = "localhost:9092";

    /**
     * Admin client dedicated to health checks. It connects lazily, so creating it never fails
     * when Kafka is down.
     *
     * @param properties    indicator settings
     * @param binderBrokers the brokers of the Spring Cloud Stream Kafka binder, may be empty
     * @return the admin client; closed when the context shuts down
     */
    @Bean(destroyMethod = "close")
    public Admin kafkaHealthAdmin(final KafkaHealthProperties properties,
            @Value("${spring.cloud.stream.kafka.binder.brokers:}") final String binderBrokers) {
        final int timeout = (int) properties.getTimeout().toMillis();
        return Admin.create(Map.of(
            AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers(properties, binderBrokers),
            AdminClientConfig.CLIENT_ID_CONFIG, "articulorum-health",
            AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, timeout,
            AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, timeout));
    }

    /**
     * The {@code kafka} health indicator.
     *
     * @param kafkaHealthAdmin the admin client
     * @param properties       indicator settings
     * @param binderBrokers    the brokers of the Spring Cloud Stream Kafka binder, may be empty
     * @return the indicator
     */
    @Bean
    public KafkaHealthIndicator kafkaHealthIndicator(final Admin kafkaHealthAdmin,
            final KafkaHealthProperties properties,
            @Value("${spring.cloud.stream.kafka.binder.brokers:}") final String binderBrokers) {
        return new KafkaHealthIndicator(kafkaHealthAdmin, bootstrapServers(properties, binderBrokers),
            properties.getTimeout());
    }

    /**
     * Chooses the brokers to check: the indicator's own setting, then the binder's, then
     * {@value #DEFAULT_BROKERS}.
     *
     * @param properties    indicator settings
     * @param binderBrokers the binder's brokers, may be empty
     * @return the bootstrap servers
     */
    static String bootstrapServers(final KafkaHealthProperties properties, final String binderBrokers) {
        if (StringUtils.hasText(properties.getBootstrapServers())) {
            return properties.getBootstrapServers();
        }
        return StringUtils.hasText(binderBrokers) ? binderBrokers : DEFAULT_BROKERS;
    }

}
