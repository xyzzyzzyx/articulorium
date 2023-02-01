package com.articulorium.ai.topology;

/**
 * A topology as the topology API lists it.
 *
 * @param id          the identifier
 * @param name        the display name
 * @param description what it is for
 * @param builtIn     whether it ships with the topology service
 * @param rootLabel   the label of its root node
 * @param depth       its number of levels
 */
public record TopologySummary(String id, String name, String description, Boolean builtIn, String rootLabel,
        Integer depth) {
}
