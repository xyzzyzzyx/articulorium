package com.articulorium.domain.vocabulary;

/**
 * Terms of the Articulorium repository vocabulary ({@code art:}): the community, sub-community
 * and collection structure, the container of academic profiles and the links between resources
 * and the PCDM topologies that built them.
 */
public final class Articulorium {

    /** The Articulorium namespace. */
    public static final String NAMESPACE = "https://articulorium.org/ns/repository#";

    /** Preferred prefix of {@link #NAMESPACE}. */
    public static final String PREFIX = "art";

    /**
     * A community: an organisational unit that contains sub-communities and collections. A
     * sub-community is a community contained by another community.
     */
    public static final String COMMUNITY = NAMESPACE + "Community";

    /** A collection inside a community (also typed {@code pcdm:Collection}). */
    public static final String COLLECTION = NAMESPACE + "Collection";

    /** The container holding the top-level communities. */
    public static final String COMMUNITY_ROOT = NAMESPACE + "CommunityRoot";

    /**
     * The container holding the academic profiles: one resource per person, described with the
     * VIVO ontology ({@code foaf:Person}, {@code vcard:}, {@code vivo:}).
     */
    public static final String PROFILE_ROOT = NAMESPACE + "ProfileRoot";

    /**
     * Link relation pointing from a member of an archival group to the group
     * ({@code Link: <group>; rel="https://articulorium.org/ns/repository#archivalGroup"}).
     */
    public static final String ARCHIVAL_GROUP_REL = NAMESPACE + "archivalGroup";

    /** The identifier of the topology a resource was built from. */
    public static final String TOPOLOGY = NAMESPACE + "topology";

    /** The key of the topology node a resource instantiates. */
    public static final String TOPOLOGY_NODE = NAMESPACE + "topologyNode";

    /** The container holding the records harvested from external repositories, one container per source. */
    public static final String HARVEST_ROOT = NAMESPACE + "HarvestRoot";

    /**
     * The container of the records harvested from one external repository; it carries the source's
     * {@link #HARVEST_CHECKPOINT}.
     */
    public static final String HARVEST_SOURCE_CONTAINER = NAMESPACE + "HarvestSource";

    /** A record harvested from an external repository. */
    public static final String HARVESTED_RECORD = NAMESPACE + "HarvestedRecord";

    /** The identifier of the source a record was harvested from (a literal). */
    public static final String HARVEST_SOURCE = NAMESPACE + "harvestSource";

    /** The identifier of a harvested record in its source (a literal: OAI identifier, UUID, IRI, path). */
    public static final String HARVEST_IDENTIFIER = NAMESPACE + "harvestIdentifier";

    /** When the source last changed a harvested record ({@code xsd:dateTime}), when the source says. */
    public static final String HARVEST_DATESTAMP = NAMESPACE + "harvestDatestamp";

    /** When a record was last harvested ({@code xsd:dateTime}). */
    public static final String HARVESTED_AT = NAMESPACE + "harvestedAt";

    /** A set (collection, community, OAI set) the source puts a harvested record in (a literal). */
    public static final String HARVEST_SET = NAMESPACE + "harvestSet";

    /** A file of a harvested record, left in the source (an IRI described with ebucore/premis terms). */
    public static final String HARVEST_FILE = NAMESPACE + "harvestFile";

    /**
     * Where the next incremental harvest of a source starts ({@code xsd:dateTime}, on the source's
     * {@link #HARVEST_SOURCE_CONTAINER} container).
     */
    public static final String HARVEST_CHECKPOINT = NAMESPACE + "harvestCheckpoint";

    /**
     * An agent's suggestion of where a harvested record belongs (its collection and topology),
     * stored below the record's copy and reviewed by a person.
     */
    public static final String CLASSIFICATION = NAMESPACE + "Classification";

    /** Type of a {@link #CLASSIFICATION} that waits for a person (pending, or failed and to retry). */
    public static final String REVIEW_PENDING = NAMESPACE + "ReviewPending";

    /** Where a classification stands: {@code PENDING}, {@code PLACING}, {@code ACCEPTED}, {@code REJECTED}, {@code FAILED}. */
    public static final String CLASSIFICATION_STATUS = NAMESPACE + "classificationStatus";

    /** The suggestion of a classification, as the agent's JSON document (a literal). */
    public static final String CLASSIFICATION_SUGGESTION = NAMESPACE + "classificationSuggestion";

    /** How sure the agent was of a classification ({@code xsd:decimal} from 0 to 1). */
    public static final String CLASSIFICATION_CONFIDENCE = NAMESPACE + "classificationConfidence";

    /** Why a classification failed (a reason code such as {@code MODEL_UNAVAILABLE}). */
    public static final String CLASSIFICATION_FAILURE = NAMESPACE + "classificationFailure";

    /** When a record was classified ({@code xsd:dateTime}). */
    public static final String CLASSIFIED_AT = NAMESPACE + "classifiedAt";

    /** Who accepted or rejected a classification (a WebID, or {@code auto}). */
    public static final String DECIDED_BY = NAMESPACE + "decidedBy";

    /** When a classification was accepted or rejected ({@code xsd:dateTime}). */
    public static final String DECIDED_AT = NAMESPACE + "decidedAt";

    /**
     * Where an accepted harvested record was placed: its item in a collection. On the record's
     * classification and on its {@link #HARVEST_PLACEMENT}.
     */
    public static final String PLACED_AT = NAMESPACE + "placedAt";

    /**
     * What is left where a harvested record was ingested once it has been placed in a collection:
     * a pointer to its item ({@link #PLACED_AT}), so later harvests update the item and nothing is
     * held twice.
     */
    public static final String HARVEST_PLACEMENT = NAMESPACE + "HarvestPlacement";

    private Articulorium() {
        // constants holder
    }

}
