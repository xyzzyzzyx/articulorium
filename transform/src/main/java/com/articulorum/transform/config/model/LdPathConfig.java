package com.articulorum.transform.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Component
@NoArgsConstructor
@ConfigurationProperties(prefix = "ldpath")
public class LdPathConfig {

    private String name = "Articulorum Web API";

    private String namespace = "http://api:8080";

    private Long defaultExpiry = 5L;

    private String cacheLocation = "cache";

}