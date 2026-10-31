package com.articulorum.topology.model;

/**
 * A topology: the shape of a kind of digital object as a tree of PCDM nodes.
 *
 * @param id          the identifier (a slug, for example {@code book})
 * @param name        the display name
 * @param description what the topology is for
 * @param builtIn     whether it ships with the service (built-in topologies cannot be changed)
 * @param root        the root node
 */
public record Topology(String id, String name, String description, boolean builtIn, TopologyNode root) {

    /**
     * Returns a copy with another identifier and built-in flag.
     *
     * @param newId      the identifier
     * @param newBuiltIn the flag
     * @return the copy
     */
    public Topology with(final String newId, final boolean newBuiltIn) {
        return new Topology(newId, name, description, newBuiltIn, root);
    }

}
