package com.articulorium.harvest.classify;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Where the AI agent suggests a harvested record belongs, as its {@code /classifications} API
 * answers: an existing collection, a new collection in an existing community, a new community with a
 * new collection, or nowhere it can tell; and the topology of the record's item.
 *
 * @param placement     {@code EXISTING_COLLECTION}, {@code NEW_COLLECTION}, {@code NEW_COMMUNITY} or {@code NONE}
 * @param collection    the existing collection
 * @param community     the existing community a new collection or community goes in
 * @param newCommunity  the proposed community
 * @param newCollection the proposed collection
 * @param topology      the topology of the record's item
 * @param confidence    how sure the agent is, 0 to 1
 * @param rationale     why
 * @param alternatives  other existing collections that could fit
 * @param model         the model that classified the record
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Suggestion(String placement, Ref collection, Ref community, NewStructure newCommunity,
        NewStructure newCollection, Topology topology, Double confidence, String rationale,
        List<Alternative> alternatives, String model) {

    /** Placement in an existing collection. */
    public static final String EXISTING_COLLECTION = "EXISTING_COLLECTION";

    /** Placement in a new collection of an existing community. */
    public static final String NEW_COLLECTION = "NEW_COLLECTION";

    /** Placement in a new collection of a new community. */
    public static final String NEW_COMMUNITY = "NEW_COMMUNITY";

    /** No placement suggested. */
    public static final String NONE = "NONE";

    /**
     * Returns the confidence, 0 when unknown.
     *
     * @return the confidence
     */
    public double confidenceOrZero() {
        return confidence == null ? 0 : confidence;
    }

    /**
     * An existing community or collection.
     *
     * @param path  its path
     * @param title its title
     * @param trail its titles from the top
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Ref(String path, String title, String trail) {
    }

    /**
     * A proposed community or collection.
     *
     * @param title       its title
     * @param description its description
     * @param slug        its suggested resource name
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NewStructure(String title, String description, String slug) {
    }

    /**
     * The topology of the record's item.
     *
     * @param kind        {@code EXISTING}, {@code NEW} or {@code NONE}
     * @param id          the existing topology's id
     * @param name        its name, or the proposed topology's
     * @param description what a proposed topology is for
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Topology(String kind, String id, String name, String description) {

        /** An existing topology. */
        public static final String EXISTING = "EXISTING";

        /** A proposed topology. */
        public static final String NEW = "NEW";

    }

    /**
     * Another collection that could fit.
     *
     * @param collection the collection
     * @param confidence how well, 0 to 1
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Alternative(Ref collection, Double confidence) {
    }

}
