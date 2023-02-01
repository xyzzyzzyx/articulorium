package com.articulorum.index.config.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Component
@NoArgsConstructor
@ConfigurationProperties(prefix = "solr")
public class SolrConfig {

    private List<String> urls = new ArrayList<>();

    private List<String> zkHosts = new ArrayList<>();

    private Optional<String> zkChroot = Optional.empty();

    private int connectionTimeout = 10000;

    private int socketTimeout = 60000;

}