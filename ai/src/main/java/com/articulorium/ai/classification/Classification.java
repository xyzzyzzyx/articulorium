package com.articulorium.ai.classification;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Where a harvested record belongs, as the agent suggests it: an existing collection, a new
 * collection in an existing community, a new community with a new collection, or nowhere it can
 * tell; and the topology its item follows. Proposals of new communities, collections and
 * topologies are only suggestions: the caller creates them, when a person accepts them or (the
 * harvest service's automatic acceptance) at once.
 *
 * @param placement    where the record goes
 * @param collection   the existing collection ({@link Placement#EXISTING_COLLECTION})
 * @param community    the existing community a new collection or community goes in, {@code null}
 *                     for a new top-level community
 * @param newCommunity the new community ({@link Placement#NEW_COMMUNITY})
 * @param newCollection the new collection ({@link Placement#NEW_COLLECTION},
 *                     {@link Placement#NEW_COMMUNITY})
 * @param topology     the topology of the record's item
 * @param confidence   how sure the agent is, 0 to 1
 * @param rationale    why, in a sentence or two
 * @param alternatives other existing collections that could fit, with their confidence
 * @param model        the model that classified it
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Classification(Placement placement, Ref collection, Ref community, NewStructure newCommunity,
        NewStructure newCollection, TopologyChoice topology, double confidence, String rationale,
        List<Alternative> alternatives, String model) {

    /** Where a record goes. */
    public enum Placement {

        /** In an existing collection. */
        EXISTING_COLLECTION,

        /** In a new collection of an existing community. */
        NEW_COLLECTION,

        /** In a new collection of a new community. */
        NEW_COMMUNITY,

        /** The agent cannot tell; a person decides. */
        NONE

    }

    /** What topology the record's item follows. */
    public enum TopologyKind {

        /** An existing topology. */
        EXISTING,

        /** A new topology (to draft and save before the record is accepted with it). */
        NEW,

        /** None: the record becomes a plain PCDM object. */
        NONE

    }

    /**
     * An existing community or collection.
     *
     * @param path  its path
     * @param title its title
     * @param trail its titles from the top
     */
    public record Ref(String path, String title, String trail) {
    }

    /**
     * A community or collection to create.
     *
     * @param title       its title
     * @param description its description
     * @param slug        its suggested resource name
     */
    public record NewStructure(String title, String description, String slug) {
    }

    /**
     * The topology of the record's item.
     *
     * @param kind        existing, new or none
     * @param id          the existing topology's id
     * @param name        its name, or the name of the new one
     * @param description what a new topology is for
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TopologyChoice(TopologyKind kind, String id, String name, String description) {
    }

    /**
     * Another collection that could fit.
     *
     * @param collection the collection
     * @param confidence how well, 0 to 1
     */
    public record Alternative(Ref collection, double confidence) {
    }

}
