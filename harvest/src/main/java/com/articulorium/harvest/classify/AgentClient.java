package com.articulorium.harvest.classify;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.articulorium.domain.vocabulary.Rdf;
import com.articulorium.harvest.core.HarvestedFile;
import com.articulorium.harvest.core.HarvestedRecord;
import com.articulorium.harvest.ingest.PlatformAuthorization;
import com.articulorium.harvest.source.DublinCore;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.StringUtils;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Asks the AI agent ({@code POST /classifications} of the {@code ai} service) where a harvested
 * record belongs, with the harvester's own platform credentials: the agent reads the community
 * structure and the topologies as the harvester may.
 *
 * <p>The record is described by its texts (titles, descriptions, subjects, creators, dates, Dublin
 * Core types), its {@code rdf:type}s, its sets in the source and the media types of its files.</p>
 */
public class AgentClient {

    private static final Logger LOG = LoggerFactory.getLogger(AgentClient.class);

    private static final List<String> TITLES = List.of(DublinCore.DCTERMS + "title", DublinCore.DC_ELEMENTS + "title",
        Rdf.RDFS_LABEL, "http://xmlns.com/foaf/0.1/name");

    private static final List<String> DESCRIPTIONS = List.of(DublinCore.DCTERMS + "description",
        DublinCore.DCTERMS + "abstract", DublinCore.DC_ELEMENTS + "description");

    private static final List<String> SUBJECTS = List.of(DublinCore.DCTERMS + "subject",
        DublinCore.DC_ELEMENTS + "subject", "http://vivoweb.org/ontology/core#freetextKeyword");

    private static final List<String> CREATORS = List.of(DublinCore.DCTERMS + "creator",
        DublinCore.DC_ELEMENTS + "creator", DublinCore.DCTERMS + "contributor", DublinCore.DC_ELEMENTS + "contributor");

    private static final List<String> DATES = List.of(DublinCore.DCTERMS + "date", DublinCore.DCTERMS + "issued",
        DublinCore.DCTERMS + "created", DublinCore.DC_ELEMENTS + "date");

    private static final List<String> GENRES = List.of(DublinCore.DCTERMS + "type", DublinCore.DC_ELEMENTS + "type");

    private final RestClient client;

    private final String base;

    private final PlatformAuthorization authorization;

    private final JsonMapper json = JsonMapper.builder().build();

    /**
     * Creates the client.
     *
     * @param client        the HTTP client (load balanced when configured, with the classification timeout)
     * @param aiUrl         where the agent is reached
     * @param authorization the harvester's credentials
     */
    public AgentClient(final RestClient client, final String aiUrl, final PlatformAuthorization authorization) {
        this.client = client;
        this.base = aiUrl.endsWith("/") ? aiUrl : aiUrl + "/";
        this.authorization = authorization;
    }

    /**
     * Classifies a record.
     *
     * @param context the container the record was ingested into (the source's container)
     * @param source  the source
     * @param record  the record
     * @return the agent's suggestion
     * @throws AgentUnavailableException when the agent cannot classify it (the reason says why)
     */
    public Suggestion classify(final String context, final String source, final HarvestedRecord record) {
        final Request request = request(context, source, record);
        try {
            final Suggestion suggestion = client.post().uri(URI.create(base + "classifications"))
                .headers(headers -> {
                    final String value = authorization.header();
                    if (StringUtils.hasText(value)) {
                        headers.set(HttpHeaders.AUTHORIZATION, value);
                    }
                })
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(Suggestion.class);
            if (suggestion == null || suggestion.placement() == null) {
                throw new AgentUnavailableException("INVALID_OUTPUT", "The agent answered no classification");
            }
            return suggestion;
        } catch (RestClientResponseException e) {
            final String reason = reason(e);
            LOG.warn("The AI agent answered {} ({}) to classifying {} of {}", e.getStatusCode().value(), reason,
                record.identifier(), source);
            throw new AgentUnavailableException(reason, "The AI agent answered " + e.getStatusCode().value(), e);
        } catch (ResourceAccessException e) {
            LOG.warn("The AI agent cannot be reached to classify {} of {}: {}", record.identifier(), source,
                e.getMessage());
            throw new AgentUnavailableException("AGENT_UNAVAILABLE", "The AI agent cannot be reached", e);
        } catch (RestClientException e) {
            throw new AgentUnavailableException("INVALID_OUTPUT", "The AI agent answered unreadably: " + e.getMessage(), e);
        } catch (IllegalStateException e) {
            // the load balancer knows no instance of the agent (not deployed, or not registered yet)
            LOG.warn("The AI agent is not available to classify {} of {}: {}", record.identifier(), source,
                e.getMessage());
            throw new AgentUnavailableException("AGENT_UNAVAILABLE", "The AI agent is not available: " + e.getMessage(),
                e);
        }
    }

    private String reason(final RestClientResponseException e) {
        try {
            final JsonNode problem = json.readTree(e.getResponseBodyAsByteArray());
            if (problem != null && problem.path("reason").isString()) {
                return problem.path("reason").asString();
            }
        } catch (RuntimeException ignored) {
            // not a problem document
        }
        return e.getStatusCode().value() == 404 ? "AGENT_UNAVAILABLE" : "HTTP_" + e.getStatusCode().value();
    }

    /**
     * Describes a record for the agent.
     *
     * @param context the container the record was ingested into
     * @param source  the source
     * @param record  the record
     * @return the request
     */
    static Request request(final String context, final String source, final HarvestedRecord record) {
        final Model model = record.metadata();
        final Resource subject = model.createResource(record.subject());
        final List<String> types = new ArrayList<>();
        model.listObjectsOfProperty(subject, model.createProperty(Rdf.TYPE)).forEachRemaining(node -> {
            if (node.isURIResource()) {
                types.add(node.asResource().getURI());
            }
        });
        final Set<String> mediaTypes = new LinkedHashSet<>();
        for (final HarvestedFile file : record.files()) {
            if (file.mediaType() != null) {
                mediaTypes.add(file.mediaType());
            }
        }
        return new Request(context, source, record.identifier(), texts(model, subject, TITLES),
            texts(model, subject, DESCRIPTIONS), texts(model, subject, SUBJECTS), texts(model, subject, CREATORS),
            texts(model, subject, DATES), texts(model, subject, GENRES), types, record.sets(), List.copyOf(mediaTypes));
    }

    /**
     * Returns the first title of a record.
     *
     * @param record the record
     * @return its title, or its identifier
     */
    static String title(final HarvestedRecord record) {
        final List<String> titles = texts(record.metadata(), record.metadata().createResource(record.subject()), TITLES);
        return titles.isEmpty() ? record.identifier() : titles.get(0);
    }

    private static List<String> texts(final Model model, final Resource subject, final List<String> predicates) {
        final Set<String> values = new LinkedHashSet<>();
        for (final String predicate : predicates) {
            model.listObjectsOfProperty(subject, model.createProperty(predicate)).forEachRemaining((RDFNode node) -> {
                if (node.isLiteral() && !node.asLiteral().getLexicalForm().isBlank()) {
                    values.add(node.asLiteral().getLexicalForm().strip());
                } else if (node.isResource()) {
                    for (final String label : TITLES) {
                        model.listObjectsOfProperty(node.asResource(), model.createProperty(label)).forEachRemaining(
                            value -> {
                                if (value.isLiteral()) {
                                    values.add(value.asLiteral().getLexicalForm().strip());
                                }
                            });
                    }
                }
            });
        }
        return values.stream().limit(20).toList();
    }

    /**
     * The agent's classification request.
     *
     * @param context      the container the caller ingests into
     * @param source       the source
     * @param identifier   the record's identifier
     * @param titles       titles
     * @param descriptions descriptions
     * @param subjects     subjects
     * @param creators     creators
     * @param dates        dates
     * @param genres       Dublin Core types
     * @param types        rdf:types
     * @param sets         sets in the source
     * @param mediaTypes   media types of the files
     */
    record Request(String context, String source, String identifier, List<String> titles, List<String> descriptions,
            List<String> subjects, List<String> creators, List<String> dates, List<String> genres, List<String> types,
            List<String> sets, List<String> mediaTypes) {
    }

    /** The agent could not classify a record. */
    public static class AgentUnavailableException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        private final String reason;

        /**
         * Creates the exception.
         *
         * @param reason  a reason code ({@code AGENT_UNAVAILABLE}, the agent's own reasons such as
         *                {@code MODEL_UNAVAILABLE}, {@code MODEL_ERROR}, {@code DISABLED})
         * @param message the diagnostic
         */
        public AgentUnavailableException(final String reason, final String message) {
            super(message);
            this.reason = reason;
        }

        /**
         * Creates the exception.
         *
         * @param reason  the reason code
         * @param message the diagnostic
         * @param cause   the cause
         */
        public AgentUnavailableException(final String reason, final String message, final Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        /**
         * Returns the reason code.
         *
         * @return the reason
         */
        public String getReason() {
            return reason;
        }

    }

}
