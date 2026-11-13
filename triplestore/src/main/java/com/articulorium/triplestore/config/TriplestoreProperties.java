package com.articulorium.triplestore.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings of the SPARQL store the triplestore module mirrors resources into
 * ({@code articulorium.triplestore.*}).
 *
 * <p>{@link #backend} selects the store. The default is
 * <a href="https://github.com/ad-freiburg/qlever">QLever</a>, which serves SPARQL 1.1 queries,
 * SPARQL 1.1 Update and the SPARQL 1.1 Graph Store HTTP Protocol at the root of its HTTP port and
 * requires an access token for every change; its settings are the ones directly under the prefix.
 * Blazegraph and Apache Jena Fuseki have settings of their own ({@link #blazegraph},
 * {@link #jena}), read only when they are selected.</p>
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorium.triplestore")
public class TriplestoreProperties {

    /** The SPARQL store resources are mirrored into. QLever unless set. */
    private Backend backend = Backend.QLEVER;

    /** Root URL of QLever. */
    private String serviceUrl = "http://qlever:7001";

    /** Path of the SPARQL query and update endpoint below {@link #serviceUrl} (QLever: the root). */
    private String sparqlPath = "";

    /** Path of the Graph Store HTTP Protocol endpoint below {@link #serviceUrl} (QLever: the root). */
    private String graphStorePath = "";

    /**
     * Access token sent as {@code Authorization: Bearer} with every request to QLever, which
     * requires it for updates.
     */
    private String accessToken;

    /** When set, resource IRIs (graph names) are minted under this base instead of the platform's. */
    private String baseUri;

    /** Blazegraph settings, used when {@link #backend} is {@link Backend#BLAZEGRAPH}. */
    private Blazegraph blazegraph = new Blazegraph();

    /** Apache Jena Fuseki settings, used when {@link #backend} is {@link Backend#JENA}. */
    private Jena jena = new Jena();

    /** SPARQL store backends. */
    public enum Backend {

        /** QLever ({@code articulorium.triplestore.service-url}, {@code access-token}, ...). */
        QLEVER,

        /** Blazegraph ({@code articulorium.triplestore.blazegraph.*}). */
        BLAZEGRAPH,

        /** Apache Jena Fuseki ({@code articulorium.triplestore.jena.*}). */
        JENA

    }

    /**
     * Returns QLever's SPARQL query and update endpoint.
     *
     * @return for example {@code http://qlever:7001/}
     */
    public String sparqlEndpoint() {
        return join(serviceUrl, sparqlPath);
    }

    /**
     * Returns QLever's Graph Store HTTP Protocol endpoint.
     *
     * @return for example {@code http://qlever:7001/}
     */
    public String graphStoreEndpoint() {
        return join(serviceUrl, graphStorePath);
    }

    /**
     * Blazegraph settings ({@code articulorium.triplestore.blazegraph.*}).
     */
    @Getter
    @Setter
    public static class Blazegraph {

        /** URL of the Blazegraph web application, the parent of its {@code namespace} resource. */
        private String serviceUrl = "http://blazegraph:8080/bigdata";

        /** Namespace the graphs are written to; created in quads mode when it does not exist. */
        private String namespace = "articulorium";

        /** User name for HTTP Basic authentication; empty for a Blazegraph without authentication. */
        private String username;

        /** Password for HTTP Basic authentication. */
        private String password;

        /**
         * Returns the SPARQL query and update endpoint of the namespace.
         *
         * @return for example {@code http://blazegraph:8080/bigdata/namespace/articulorium/sparql}
         */
        public String sparqlEndpoint() {
            return join(serviceUrl, "namespace/" + trim(namespace) + "/sparql");
        }

        /**
         * Returns the resource namespaces are created under.
         *
         * @return for example {@code http://blazegraph:8080/bigdata/namespace}
         */
        public String namespacesEndpoint() {
            return join(serviceUrl, "namespace");
        }

        /**
         * Returns the resource describing the properties of the namespace.
         *
         * @return for example {@code http://blazegraph:8080/bigdata/namespace/articulorium/properties}
         */
        public String namespacePropertiesEndpoint() {
            return join(serviceUrl, "namespace/" + trim(namespace) + "/properties");
        }

    }

    /**
     * Apache Jena Fuseki settings ({@code articulorium.triplestore.jena.*}).
     */
    @Getter
    @Setter
    public static class Jena {

        /** Root URL of the Fuseki server. */
        private String serviceUrl = "http://fuseki:3030";

        /** Dataset the graphs are written to; it must exist and allow updates. */
        private String dataset = "articulorium";

        /** User name for HTTP Basic authentication; empty for a Fuseki without authentication. */
        private String username;

        /** Password for HTTP Basic authentication. */
        private String password;

        /**
         * Returns the URL of the dataset.
         *
         * @return for example {@code http://fuseki:3030/articulorium}
         */
        public String datasetUrl() {
            return join(serviceUrl, trim(dataset));
        }

        /**
         * Returns the SPARQL query endpoint of the dataset.
         *
         * @return for example {@code http://fuseki:3030/articulorium/sparql}
         */
        public String queryEndpoint() {
            return datasetUrl() + "/sparql";
        }

        /**
         * Returns the SPARQL update endpoint of the dataset.
         *
         * @return for example {@code http://fuseki:3030/articulorium/update}
         */
        public String updateEndpoint() {
            return datasetUrl() + "/update";
        }

        /**
         * Returns the Graph Store HTTP Protocol endpoint of the dataset.
         *
         * @return for example {@code http://fuseki:3030/articulorium/data}
         */
        public String graphStoreEndpoint() {
            return datasetUrl() + "/data";
        }

    }

    private static String trim(final String segment) {
        return segment.strip().replaceAll("^/+|/+$", "");
    }

    private static String join(final String url, final String path) {
        final String root = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        if (path == null || path.isBlank() || "/".equals(path)) {
            return root + "/";
        }
        return root + (path.startsWith("/") ? path : "/" + path);
    }

}
