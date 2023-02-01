package com.articulorum.index;

import com.articulorum.index.config.model.SolrConfig;

import org.apache.solr.client.solrj.SolrClient;
import org.apache.solr.client.solrj.impl.CloudSolrClient;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;
import org.springframework.integration.config.EnableIntegration;

@EnableIntegration
@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = "com.articulorum")
public class IndexApplication {

    public static void main(String[] args) {
        SpringApplication.run(IndexApplication.class, args);
    }

    @Bean
    public SolrClient getCloudSolrClient(SolrConfig solrConfig) {
        return new CloudSolrClient.Builder(solrConfig.getZkHosts(), solrConfig.getZkChroot())
            .withConnectionTimeout(solrConfig.getConnectionTimeout())
            .withSocketTimeout(solrConfig.getSocketTimeout())
            .build();
    }

}
