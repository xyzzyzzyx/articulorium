package com.articulorum.triplestore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings of the SPARQL store the triplestore module mirrors resources into
 * ({@code articulorum.triplestore.*}).
 *
 * <p>The default store is <a href="https://github.com/ad-freiburg/qlever">QLever</a>, which serves
 * SPARQL 1.1 queries, SPARQL 1.1 Update and the SPARQL 1.1 Graph Store HTTP Protocol at the root
 * of its HTTP port and requires an access token for every change. Any store implementing the same
 * protocols works when its endpoint paths are configured.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorum.triplestore")
public class TriplestoreProperties {

    /** Root URL of the store. */
    private String serviceUrl = "http://qlever:7001";

    /** Path of the SPARQL query and update endpoint below {@link #serviceUrl} (QLever: the root). */
    private String sparqlPath = "";

    /** Path of the Graph Store HTTP Protocol endpoint below {@link #serviceUrl} (QLever: the root). */
    private String graphStorePath = "";

    /**
     * Access token sent as {@code Authorization: Bearer} with every request; QLever requires it for
     * updates. Empty for stores without authentication.
     */
    private String accessToken;

    /** When set, resource IRIs (graph names) are minted under this base instead of the platform's. */
    private String baseUri;

    /**
     * Returns the SPARQL query and update endpoint.
     *
     * @return for example {@code http://qlever:7001/}
     */
    public String sparqlEndpoint() {
        return join(serviceUrl, sparqlPath);
    }

    /**
     * Returns the Graph Store HTTP Protocol endpoint.
     *
     * @return for example {@code http://qlever:7001/}
     */
    public String graphStoreEndpoint() {
        return join(serviceUrl, graphStorePath);
    }

    private static String join(final String url, final String path) {
        final String root = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        if (path == null || path.isBlank() || "/".equals(path)) {
            return root + "/";
        }
        return root + (path.startsWith("/") ? path : "/" + path);
    }

}
