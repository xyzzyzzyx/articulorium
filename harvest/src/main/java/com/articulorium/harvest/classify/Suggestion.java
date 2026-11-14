package com.articulorium.harvest.classify;

import java.util.List;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Where the AI agent suggests a harvested record belongs, as its {@code /classifications} API
 * answers: an existing collection, a new collection in an existing community, new communities
 * (nested from the more general to the more specific) with a new collection, or nowhere it can
 * tell; and the topology of the record's item.
 *
 * @param placement      {@code EXISTING_COLLECTION}, {@code NEW_COLLECTION}, {@code NEW_COMMUNITY} or {@code NONE}
 * @param collection     the existing collection
 * @param community      the existing community a new collection or the first new community goes in
 * @param newCommunities the proposed communities, from the more general to the more specific: the
 *                       first in {@code community} (or at the top), each next one in the one before,
 *                       the proposed collection in the last
 * @param topology      the topology of the record's item
 * @param confidence    how sure the agent is, 0 to 1
 * @param rationale     why
 * @param alternatives  other existing collections that could fit
 * @param model         the model that classified the record
 * @param newCommunity  read only, from suggestions stored before communities were nested: the single
 *                      proposed community, which becomes {@code newCommunities}; never written
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record Suggestion(String placement, Ref collection, Ref community, List<NewStructure> newCommunities,
        NewStructure newCollection, Topology topology, Double confidence, String rationale,
        List<Alternative> alternatives, String model,
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY) NewStructure newCommunity) {

    /**
     * Folds the single proposed community of a stored suggestion into {@code newCommunities}.
     */
    public Suggestion {
        if ((newCommunities == null || newCommunities.isEmpty()) && newCommunity != null) {
            newCommunities = List.of(newCommunity);
        }
        newCommunities = newCommunities == null ? null
            : newCommunities.stream().filter(Objects::nonNull).toList();
        newCommunity = null;
    }

    /**
     * Creates a suggestion.
     *
     * @param placement      where the record goes
     * @param collection     the existing collection
     * @param community      the existing community
     * @param newCommunities the proposed communities, general to specific
     * @param newCollection  the proposed collection
     * @param topology       the topology
     * @param confidence     how sure the agent is
     * @param rationale      why
     * @param alternatives   other collections
     * @param model          the model
     */
    public Suggestion(final String placement, final Ref collection, final Ref community,
            final List<NewStructure> newCommunities, final NewStructure newCollection, final Topology topology,
            final Double confidence, final String rationale, final List<Alternative> alternatives, final String model) {
        this(placement, collection, community, newCommunities, newCollection, topology, confidence, rationale,
            alternatives, model, null);
    }

    /**
     * Returns the proposed communities, general to specific.
     *
     * @return the communities, empty when none is proposed
     */
    public List<NewStructure> proposedCommunities() {
        return newCommunities == null ? List.of() : newCommunities;
    }

    /** Placement in an existing collection. */
    public static final String EXISTING_COLLECTION = "EXISTING_COLLECTION";

    /** Placement in a new collection of an existing community. */
    public static final String NEW_COLLECTION = "NEW_COLLECTION";

    /** Placement in a new collection of one or more new, nested communities. */
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
     * Returns this suggestion with another topology (the one created for a proposed topology).
     *
     * @param chosen the topology
     * @return the suggestion
     */
    public Suggestion withTopology(final Topology chosen) {
        return new Suggestion(placement, collection, community, newCommunities, newCollection, chosen, confidence,
            rationale, alternatives, model);
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
