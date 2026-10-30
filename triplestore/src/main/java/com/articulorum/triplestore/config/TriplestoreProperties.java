package com.articulorum.triplestore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * SPARQL store settings ({@code articulorum.triplestore.*}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorum.triplestore")
public class TriplestoreProperties {

    /** Blazegraph service root, for example {@code http://blazegraph:8080/bigdata}. */
    private String serviceUrl = "http://blazegraph:8080/bigdata";

    /** Blazegraph namespace (data set) holding the resources. */
    private String namespace = "articulorum";

    /** Create the namespace (in quads mode) on start-up when it does not exist. */
    private boolean createNamespace = true;

    /**
     * Base IRI used for resource and graph IRIs. When empty, the base the client used to reach
     * the platform (carried by each event) is used.
     */
    private String baseUri;

    /**
     * Returns the SPARQL 1.1 endpoint of the namespace.
     *
     * @return the endpoint URL
     */
    public String sparqlEndpoint() {
        return trim(serviceUrl) + "/namespace/" + namespace + "/sparql";
    }

    /**
     * Returns the namespace administration URL.
     *
     * @return the URL of the namespace collection
     */
    public String namespacesEndpoint() {
        return trim(serviceUrl) + "/namespace";
    }

    private static String trim(final String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

}
