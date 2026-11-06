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

    private Articulorium() {
        // constants holder
    }

}
