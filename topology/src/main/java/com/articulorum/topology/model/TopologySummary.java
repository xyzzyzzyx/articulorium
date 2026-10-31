package com.articulorum.topology.model;

/**
 * A topology in listings.
 *
 * @param id          the identifier
 * @param name        the display name
 * @param description what it is for
 * @param builtIn     whether it ships with the service
 * @param rootLabel   the label of its root node
 * @param depth       the number of levels
 */
public record TopologySummary(String id, String name, String description, boolean builtIn, String rootLabel,
        int depth) {

    /**
     * Summarises a topology.
     *
     * @param topology the topology
     * @return the summary
     */
    public static TopologySummary of(final Topology topology) {
        return new TopologySummary(topology.id(), topology.name(), topology.description(), topology.builtIn(),
            topology.root() == null ? null : topology.root().label(), depth(topology.root()));
    }

    private static int depth(final TopologyNode node) {
        if (node == null) {
            return 0;
        }
        int deepest = 0;
        for (final ChildRule child : node.children()) {
            deepest = Math.max(deepest, depth(child.node()));
        }
        return deepest + 1;
    }

}
