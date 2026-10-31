package com.articulorum.topology.model;

import java.util.List;

/**
 * The outcome of a build: the resources created, root first.
 *
 * @param path      path of the root resource created
 * @param topology  the topology identifier
 * @param nodeKey   the node the root resource instantiates
 * @param resources paths of every resource created, in creation order
 */
public record BuildResult(String path, String topology, String nodeKey, List<String> resources) {
}
