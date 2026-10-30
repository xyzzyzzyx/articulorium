package com.articulorum.transform.fieldmapping;

import java.util.List;
import java.util.Objects;

/**
 * The definition of one document field: the property paths whose values fill it and the type
 * they are converted to.
 *
 * <p>A path is a sequence of predicate IRIs followed from the resource; for example
 * {@code [dcterms:title]} reads the resource's titles, {@code [pcdm:hasMember, dcterms:title]}
 * the titles of its members. Values of all paths are combined (the {@code |} of LDPath).</p>
 *
 * @param name  the field name
 * @param paths the property paths, each a list of absolute predicate IRIs
 * @param type  the value type
 */
public record FieldMapping(String name, List<List<String>> paths, FieldType type) {

    /**
     * Creates a definition.
     *
     * @param name  the field name, not blank
     * @param paths the property paths, at least one, none empty
     * @param type  the value type, {@code null} for {@link FieldType#STRING}
     */
    public FieldMapping {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("A field needs a name");
        }
        Objects.requireNonNull(paths, "paths");
        if (paths.isEmpty() || paths.stream().anyMatch(path -> path == null || path.isEmpty())) {
            throw new IllegalArgumentException("Field " + name + " needs at least one non-empty path");
        }
        paths = paths.stream().map(List::copyOf).toList();
        type = type == null ? FieldType.STRING : type;
    }

}
