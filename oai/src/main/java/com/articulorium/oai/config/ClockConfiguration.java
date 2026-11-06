package com.articulorium.oai.config;

import java.time.Clock;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The clock of OAI-PMH response dates (replaced in tests).
 */
@Configuration(proxyBeanMethods = false)
public class ClockConfiguration {

    /**
     * The system clock in UTC.
     *
     * @return the clock
     */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }

}
