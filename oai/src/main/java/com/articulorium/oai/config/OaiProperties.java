package com.articulorium.oai.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.articulorium.domain.vocabulary.Pcdm;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Settings of the OAI-PMH data provider ({@code articulorium.oai.*}).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "articulorium.oai")
public class OaiProperties {

    private static final String DCTERMS = "http://purl.org/dc/terms/";

    private static final String DC = "http://purl.org/dc/elements/1.1/";

    private static final String SCHEMA = "http://schema.org/";

    /** Where this service reads the platform's LDP root (a Consul service name when load balanced). */
    private String platformUrl = "http://articulorium-platform/";

    /** Whether {@link #platformUrl} names a Consul service resolved by the load balancer. */
    private boolean loadBalanced = true;

    /**
     * Where harvesters reach the platform, relative to this service's public base (the gateway
     * serves this service under {@code /oai} and the platform under {@code /platform/}) or
     * absolute. Records identify their resources with IRIs under this URL.
     */
    private String platformPublicUrl = "../platform/";

    /** Where harvesters reach the user interface, relative to this service's public base or absolute. */
    private String uiPublicUrl = "../ui/";

    /** The {@code repositoryName} answered by Identify. */
    private String repositoryName = "Articulorium";

    /**
     * The namespace of OAI identifiers, {@code oai:<repository-identifier>:<path>}. Keep it stable:
     * harvesters key their copies by identifier.
     */
    private String repositoryIdentifier = "articulorium.org";

    /** The {@code adminEmail}s answered by Identify (at least one). */
    private List<String> adminEmails = new ArrayList<>(List.of("admin@example.org"));

    /** Largest number of records, identifiers or sets in one response. */
    private int pageSize = 100;

    /**
     * Largest number of platform search pages read to fill one list response when most results are
     * filtered out (nested records); a shorter list with a resumption token is answered after that.
     */
    private int maxScanPages = 10;

    /** The {@code rdf:type} of the resources exposed as records. */
    private String recordType = Pcdm.OBJECT;

    /**
     * Whether records nested inside other records (such as the pages of a book, which are PCDM
     * objects too) are exposed as records of their own.
     */
    private boolean includeNested = false;

    /** The container (below the LDP root) whose communities and collections are the sets. */
    private String communitiesPath = "communities";

    /** Largest number of sets read for ListSets and for set names. */
    private int maxSets = 10_000;

    /**
     * Unqualified Dublin Core element (title, creator, ...) to the RDF properties of a resource
     * whose values it carries. Setting an element replaces its default list; an empty list
     * drops the element.
     */
    private Map<String, List<String>> dublinCore = defaultDublinCore();

    private static Map<String, List<String>> defaultDublinCore() {
        final Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("title", List.of(DCTERMS + "title", DC + "title", DCTERMS + "alternative", SCHEMA + "name"));
        map.put("creator", List.of(DCTERMS + "creator", DC + "creator", SCHEMA + "creator", SCHEMA + "author"));
        map.put("subject", List.of(DCTERMS + "subject", DC + "subject", SCHEMA + "keywords"));
        map.put("description", List.of(DCTERMS + "description", DC + "description", DCTERMS + "abstract",
            DCTERMS + "tableOfContents", SCHEMA + "description"));
        map.put("publisher", List.of(DCTERMS + "publisher", DC + "publisher", SCHEMA + "publisher"));
        map.put("contributor", List.of(DCTERMS + "contributor", DC + "contributor", SCHEMA + "contributor"));
        map.put("date", List.of(DCTERMS + "date", DC + "date", DCTERMS + "issued", DCTERMS + "created",
            SCHEMA + "datePublished"));
        map.put("type", List.of(DCTERMS + "type", DC + "type"));
        map.put("format", List.of(DCTERMS + "format", DC + "format", DCTERMS + "extent"));
        map.put("identifier", List.of(DCTERMS + "identifier", DC + "identifier", DCTERMS + "bibliographicCitation",
            "http://purl.org/ontology/bibo/isbn13", "http://purl.org/ontology/bibo/isbn10",
            "http://purl.org/ontology/bibo/issn", "http://purl.org/ontology/bibo/doi"));
        map.put("source", List.of(DCTERMS + "source", DC + "source"));
        map.put("language", List.of(DCTERMS + "language", DC + "language", SCHEMA + "inLanguage"));
        map.put("relation", List.of(DCTERMS + "relation", DC + "relation", DCTERMS + "isPartOf",
            DCTERMS + "hasPart", DCTERMS + "isVersionOf", DCTERMS + "references", DCTERMS + "isReferencedBy"));
        map.put("coverage", List.of(DCTERMS + "coverage", DC + "coverage", DCTERMS + "spatial", DCTERMS + "temporal"));
        map.put("rights", List.of(DCTERMS + "rights", DC + "rights", DCTERMS + "license", DCTERMS + "accessRights",
            SCHEMA + "license"));
        return map;
    }

}
