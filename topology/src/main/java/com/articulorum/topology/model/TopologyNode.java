package com.articulorum.topology.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * One level of a topology: a PCDM collection or object with its types, metadata fields, files
 * and children.
 *
 * @param key      the node's key, unique within the topology (for example {@code page})
 * @param label    the node's label (for example {@code Page})
 * @param pcdmType {@code Collection} or {@code Object}
 * @param types    additional {@code rdf:type} IRIs (for example {@code bibo:Book})
 * @param metadata the metadata fields
 * @param files    the kinds of files the node holds
 * @param children the child nodes
 */
public record TopologyNode(String key, String label, String pcdmType, List<String> types,
        List<MetadataField> metadata, List<FileUse> files, List<ChildRule> children) {

    /** {@link #pcdmType()} of a PCDM collection. */
    public static final String COLLECTION = "Collection";

    /** {@link #pcdmType()} of a PCDM object. */
    public static final String OBJECT = "Object";

    /**
     * Creates a node with immutable lists.
     *
     * @param key      the key
     * @param label    the label
     * @param pcdmType Collection or Object
     * @param types    additional types
     * @param metadata metadata fields
     * @param files    file kinds
     * @param children child rules
     */
    public TopologyNode {
        types = types == null ? List.of() : List.copyOf(types);
        metadata = metadata == null ? List.of() : List.copyOf(metadata);
        files = files == null ? List.of() : List.copyOf(files);
        children = children == null ? List.of() : List.copyOf(children);
    }

    /**
     * Finds a node by key in this node's subtree (including itself).
     *
     * @param nodeKey the key
     * @return the node, or empty
     */
    public Optional<TopologyNode> find(final String nodeKey) {
        if (key != null && key.equals(nodeKey)) {
            return Optional.of(this);
        }
        for (final ChildRule child : children) {
            final Optional<TopologyNode> found = child.node().find(nodeKey);
            if (found.isPresent()) {
                return found;
            }
        }
        return Optional.empty();
    }

    /**
     * Finds the rule of a direct child by the child's key.
     *
     * @param childKey the child node's key
     * @return the rule, or empty
     */
    public Optional<ChildRule> childRule(final String childKey) {
        return children.stream().filter(rule -> rule.node() != null && childKey.equals(rule.node().key())).findFirst();
    }

    /**
     * Lists every node of the subtree, depth first.
     *
     * @return the nodes, starting with this one
     */
    public List<TopologyNode> flatten() {
        final List<TopologyNode> nodes = new ArrayList<>();
        nodes.add(this);
        for (final ChildRule child : children) {
            if (child.node() != null) {
                nodes.addAll(child.node().flatten());
            }
        }
        return nodes;
    }

}
