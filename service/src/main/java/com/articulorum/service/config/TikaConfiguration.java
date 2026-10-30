package com.articulorum.service.config;

import org.apache.tika.Tika;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Provides the Apache Tika facade used for media type detection.
 */
@Configuration(proxyBeanMethods = false)
public class TikaConfiguration {

    /**
     * The Tika facade; thread-safe, so one instance serves every request.
     *
     * @return the facade
     */
    @Bean
    public Tika tika() {
        return new Tika();
    }

}
