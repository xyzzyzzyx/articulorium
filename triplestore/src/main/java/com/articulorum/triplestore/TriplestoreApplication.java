package com.articulorum.triplestore;

import com.articulorum.triplestore.config.model.BlazegraphConfig;
import com.bigdata.rdf.sail.webapp.client.RemoteRepositoryManager;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;

@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = "com.articulorum")
public class TriplestoreApplication {

    public static void main(String[] args) {
        SpringApplication.run(TriplestoreApplication.class, args);
    }

    @Bean
    public RemoteRepositoryManager getRemoteRepositoryManager(BlazegraphConfig blazegraphConfig) {
        return new RemoteRepositoryManager(blazegraphConfig.getServiceUrl() , blazegraphConfig.getUseLbs());
    }

}
