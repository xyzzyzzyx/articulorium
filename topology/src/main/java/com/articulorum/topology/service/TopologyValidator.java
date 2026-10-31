package com.articulorum.topology.service;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import com.articulorum.topology.model.ChildRule;
import com.articulorum.topology.model.FileUse;
import com.articulorum.topology.model.MetadataField;
import com.articulorum.topology.model.Topology;
import com.articulorum.topology.model.TopologyNode;

/**
 * Checks topology definitions.
 *
 * <ul>
 *   <li>The identifier is a lower-case slug and the name is not blank.</li>
 *   <li>Every node has a unique key (lower-case slug), a label and a PCDM type
 *       ({@code Collection} or {@code Object}); additional types, metadata predicates and file
 *       uses are absolute IRIs.</li>
 *   <li>PCDM containment holds: collections contain collections or objects, objects contain only
 *       objects.</li>
 *   <li>Child counts are consistent ({@code 0 <= min}, {@code max} unlimited or {@code >= min}).</li>
 *   <li>Topologies stay small: at most {@value #MAX_DEPTH} levels and {@value #MAX_NODES} nodes.</li>
 * </ul>
 */
public final class TopologyValidator {

    /** Largest number of levels. */
    public static final int MAX_DEPTH = 8;

    /** Largest number of nodes. */
    public static final int MAX_NODES = 100;

    /** Pattern of identifiers and node keys. */
    static final Pattern SLUG = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");

    private TopologyValidator() {
        // utility class
    }

    /**
     * Validates a topology.
     *
     * @param topology the topology
     * @return the problems found; empty when it is valid
     */
    public static List<String> validate(final Topology topology) {
        final List<String> errors = new ArrayList<>();
        if (topology == null) {
            return List.of("A topology is required");
        }
        if (topology.id() == null || !SLUG.matcher(topology.id()).matches()) {
            errors.add("id must be a lower-case slug (a-z, 0-9, - and _)");
        }
        if (topology.name() == null || topology.name().isBlank()) {
            errors.add("name is required");
        }
        if (topology.root() == null) {
            errors.add("root node is required");
            return errors;
        }
        final Set<String> keys = new HashSet<>();
        final int[] count = {0};
        validateNode(topology.root(), null, 1, keys, count, errors);
        if (count[0] > MAX_NODES) {
            errors.add("A topology has at most " + MAX_NODES + " nodes");
        }
        return errors;
    }

    /**
     * Validates a topology and throws when it is invalid.
     *
     * @param topology the topology
     * @throws InvalidTopologyException when it is invalid
     */
    public static void check(final Topology topology) {
        final List<String> errors = validate(topology);
        if (!errors.isEmpty()) {
            throw new InvalidTopologyException(errors);
        }
    }

    private static void validateNode(final TopologyNode node, final TopologyNode parent, final int depth,
            final Set<String> keys, final int[] count, final List<String> errors) {
        count[0]++;
        final String where = "node " + (node.key() == null ? "?" : node.key());
        if (depth > MAX_DEPTH) {
            errors.add(where + " is deeper than " + MAX_DEPTH + " levels");
            return;
        }
        if (node.key() == null || !SLUG.matcher(node.key()).matches()) {
            errors.add(where + ": key must be a lower-case slug");
        } else if (!keys.add(node.key())) {
            errors.add(where + ": key is used twice");
        }
        if (node.label() == null || node.label().isBlank()) {
            errors.add(where + ": label is required");
        }
        if (!TopologyNode.COLLECTION.equals(node.pcdmType()) && !TopologyNode.OBJECT.equals(node.pcdmType())) {
            errors.add(where + ": pcdmType must be Collection or Object");
        } else if (parent != null && TopologyNode.OBJECT.equals(parent.pcdmType())
                && TopologyNode.COLLECTION.equals(node.pcdmType())) {
            errors.add(where + ": a PCDM object cannot contain a collection");
        }
        for (final String type : node.types()) {
            if (!isIri(type)) {
                errors.add(where + ": type " + type + " is not an absolute IRI");
            }
        }
        for (final MetadataField field : node.metadata()) {
            if (!isIri(field.predicate())) {
                errors.add(where + ": metadata predicate " + field.predicate() + " is not an absolute IRI");
            }
            if (field.label() == null || field.label().isBlank()) {
                errors.add(where + ": metadata field " + field.predicate() + " needs a label");
            }
            if (field.datatype() != null && !field.datatype().isBlank() && !isIri(field.datatype())) {
                errors.add(where + ": datatype " + field.datatype() + " is not an absolute IRI");
            }
        }
        for (final FileUse file : node.files()) {
            if (!isIri(file.use())) {
                errors.add(where + ": file use " + file.use() + " is not an absolute IRI");
            }
        }
        for (final ChildRule rule : node.children()) {
            if (rule.node() == null) {
                errors.add(where + ": a child rule has no node");
                continue;
            }
            if (rule.min() < 0) {
                errors.add(where + ": min of " + rule.node().key() + " must not be negative");
            }
            if (rule.max() != ChildRule.UNLIMITED && rule.max() < rule.min()) {
                errors.add(where + ": max of " + rule.node().key() + " must be -1 (unlimited) or at least min");
            }
            if (rule.initial() < 0) {
                errors.add(where + ": initial of " + rule.node().key() + " must not be negative");
            }
            validateNode(rule.node(), node, depth + 1, keys, count, errors);
        }
    }

    /**
     * Tells whether a value is an absolute IRI.
     *
     * @param value the value
     * @return {@code true} for an absolute IRI
     */
    static boolean isIri(final String value) {
        if (value == null || value.isBlank() || value.contains(" ") || value.contains("<") || value.contains(">")) {
            return false;
        }
        try {
            return URI.create(value).isAbsolute();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

}
