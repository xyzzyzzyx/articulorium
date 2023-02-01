package com.articulorum.triplestore.config.model;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Component
@NoArgsConstructor
@ConfigurationProperties(prefix = "blazegraph")
public class BlazegraphConfig {

    private String serviceUrl = "http://blazegraph:8080/bigdata";

    private Boolean useLbs = false;

}