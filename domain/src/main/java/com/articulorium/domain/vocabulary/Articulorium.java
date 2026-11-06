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

    private Articulorium() {
        // constants holder
    }

}
