package com.articulorum.transform.fieldmapping;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.articulorum.transform.config.TransformProperties;
import com.articulorum.transform.spi.TransformationContext;
import com.articulorum.transform.spi.Transformer;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.Property;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.rdf.model.StmtIterator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

/**
 * Flattens a resource into a document according to declarative {@link FieldMappings}: each field
 * collects the values reached through its property paths, converted to the field's type.
 *
 * <p>This transformer replaces the Apache Marmotta LDPath program the service used before; the
 * default mappings ({@code transform/field-mappings.yml}) produce the same fields as the former
 * {@code default.ldpath}. Paths are evaluated against the resource's own representation, which
 * the platform ships with every change event, so no request back to the platform is needed.</p>
 */
@Component
public class FieldMappingTransformer implements Transformer {

    /** Name of this transformer. */
    public static final String NAME = "field-mapping";

    private final FieldMappings mappings;

    /**
     * Creates the transformer from the configured mapping document.
     *
     * @param properties transform settings ({@code articulorum.transform.field-mappings})
     * @param loader     resolves the document location
     * @throws IOException when the document cannot be read or is invalid
     */
    @Autowired
    public FieldMappingTransformer(final TransformProperties properties, final ResourceLoader loader)
            throws IOException {
        try (InputStream in = loader.getResource(properties.getFieldMappings()).getInputStream()) {
            this.mappings = FieldMappings.load(in);
        }
    }

    /**
     * Creates the transformer from mappings.
     *
     * @param mappings the field mappings
     */
    public FieldMappingTransformer(final FieldMappings mappings) {
        this.mappings = mappings;
    }

    @Override
    public String name() {
        return NAME;
    }

    @Override
    public String description() {
        return "Maps RDF properties to document fields (" + mappings.fields().size() + " fields)";
    }

    /** @return the field mappings in use */
    public FieldMappings mappings() {
        return mappings;
    }

    @Override
    public Map<String, Collection<?>> transform(final TransformationContext context) {
        final Map<String, Collection<?>> document = new LinkedHashMap<>();
        final Resource subject = context.subject();
        for (final FieldMapping field : mappings.fields()) {
            final Set<Object> values = new LinkedHashSet<>();
            for (final List<String> path : field.paths()) {
                for (final RDFNode node : follow(context.model(), subject, path)) {
                    field.type().convert(node).ifPresent(values::add);
                }
            }
            if (!values.isEmpty()) {
                document.put(field.name(), new ArrayList<>(values));
            }
        }
        return document;
    }

    /**
     * Follows a property path from a node.
     *
     * @param model   the model
     * @param subject the start node
     * @param path    the predicate IRIs to follow, in order
     * @return the nodes reached, without duplicates, in encounter order
     */
    static Collection<RDFNode> follow(final Model model, final Resource subject, final List<String> path) {
        Collection<RDFNode> current = List.of(subject);
        for (final String predicate : path) {
            final Property property = model.createProperty(predicate);
            final Set<RDFNode> next = new LinkedHashSet<>();
            for (final RDFNode node : current) {
                if (!node.isResource()) {
                    continue;
                }
                final StmtIterator statements = model.listStatements(node.asResource(), property, (RDFNode) null);
                try {
                    statements.forEachRemaining(statement -> next.add(statement.getObject()));
                } finally {
                    statements.close();
                }
            }
            current = next;
        }
        return current;
    }

}
