package com.articulorum.transform;

import java.io.File;
import java.io.IOException;

import com.articulorum.transform.config.model.LdPathConfig;

import org.apache.marmotta.commons.http.ContentType;
import org.apache.marmotta.ldcache.api.LDCachingBackend;
import org.apache.marmotta.ldcache.backend.file.LDCachingFileBackend;
import org.apache.marmotta.ldcache.model.CacheConfiguration;
import org.apache.marmotta.ldcache.services.LDCache;
import org.apache.marmotta.ldclient.api.endpoint.Endpoint;
import org.apache.marmotta.ldclient.api.provider.DataProvider;
import org.apache.marmotta.ldclient.endpoint.rdf.LinkedDataEndpoint;
import org.apache.marmotta.ldclient.model.ClientConfiguration;
import org.apache.marmotta.ldclient.provider.rdf.CacheProvider;
import org.apache.marmotta.ldclient.provider.rdf.LinkedDataProvider;
import org.apache.marmotta.ldclient.provider.rdf.RegexUriProvider;
import org.apache.marmotta.ldclient.provider.rdf.SPARQLProvider;
import org.apache.marmotta.ldpath.backend.linkeddata.LDCacheBackend;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.context.annotation.Bean;

@EnableDiscoveryClient
@SpringBootApplication(scanBasePackages = "com.articulorum" )
public class LdpathApplication {

    private final static String ARTICULORUM_PROVIDER_NAME = "Articulorum Data Provider";

    public static void main(String[] args) {
        SpringApplication.run(LdpathApplication.class, args);
    }

    @Bean
    public LDCachingBackend cachingBackend(LdPathConfig ldpathConfig) throws IOException {
        File cache = new File(ldpathConfig.getCacheLocation());
        if (!cache.exists()) {
            cache.mkdir();
        }
        LDCachingBackend backend = new LDCachingFileBackend(cache);
        backend.initialize();
        return backend;
    }

    @Bean
    public DataProvider provider() {
        return new LinkedDataProvider() {
            @Override
            public String getName() {
                return ARTICULORUM_PROVIDER_NAME;
            }
        };
    }

    @Bean
    public Endpoint endpoint(LdPathConfig ldpathConfig) {
        String name = ldpathConfig.getName();
        String type = ARTICULORUM_PROVIDER_NAME;
        String uriPattern = "*";
        String endpointUrl = ldpathConfig.getNamespace();
        Long defaultExpiry = ldpathConfig.getDefaultExpiry();
        Endpoint endpoint = new Endpoint(name, type, uriPattern, endpointUrl, defaultExpiry);
        endpoint.setPriority(Endpoint.PRIORITY_HIGH);
        endpoint.addContentType(new ContentType("text", "turtle", 1.0));
        endpoint.addContentType(new ContentType("text", "n3", 0.8));
        endpoint.addContentType(new ContentType("application", "rdf+xml", 0.8));
        endpoint.addContentType(new ContentType("application", "ld+json", 0.5));
        return endpoint;
    }

    @Bean
    public ClientConfiguration clientConfiguration(DataProvider provider, Endpoint endpoint) {
        final ClientConfiguration client = new ClientConfiguration();
        client.addProvider(new LinkedDataProvider());
        client.addProvider(new CacheProvider());
        client.addProvider(new RegexUriProvider());
        client.addProvider(new SPARQLProvider());
        client.addProvider(provider);
        client.addEndpoint(new LinkedDataEndpoint());
        client.addEndpoint(endpoint);
        return client;
    }

    @Bean
    public CacheConfiguration cacheConfiguration(ClientConfiguration clientConfiguration, LdPathConfig ldpathConfig) {
        CacheConfiguration configuration = new CacheConfiguration();
        configuration.setClientConfiguration(clientConfiguration);
        configuration.setDefaultExpiry(ldpathConfig.getDefaultExpiry());
        return configuration;
    }

    @Bean
    public LDCache cache(CacheConfiguration cacheConfiguration, LDCachingBackend cachingBackend) throws IOException {
        return new LDCache(cacheConfiguration, cachingBackend);
    }

    @Bean
    public LDCacheBackend backend(LDCache cache) throws IOException {
        return new LDCacheBackend(cache);
    }

}
