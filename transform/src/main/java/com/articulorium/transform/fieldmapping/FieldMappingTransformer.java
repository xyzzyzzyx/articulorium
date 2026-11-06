package com.articulorium.transform.fieldmapping;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import com.articulorium.transform.config.TransformProperties;
import com.articulorium.transform.spi.TransformationContext;
import com.articulorium.transform.spi.Transformer;

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

    /** Document field listing the languages of the resource's language-tagged texts. */
    public static final String LANGUAGES_FIELD = "languages";

    /**
     * Languages with a {@code *_txt_<suffix>} dynamic field (and analyzer) in Solr's
     * {@code _default} configset, by ISO 639-1 code; Chinese and Korean use the CJK bigram one.
     */
    static final Map<String, String> SOLR_LANGUAGES = Map.ofEntries(
        Map.entry("ar", "ar"), Map.entry("bg", "bg"), Map.entry("ca", "ca"), Map.entry("cs", "cz"),
        Map.entry("da", "da"), Map.entry("de", "de"), Map.entry("el", "el"), Map.entry("en", "en"),
        Map.entry("es", "es"), Map.entry("et", "et"), Map.entry("eu", "eu"), Map.entry("fa", "fa"),
        Map.entry("fi", "fi"), Map.entry("fr", "fr"), Map.entry("ga", "ga"), Map.entry("gl", "gl"),
        Map.entry("hi", "hi"), Map.entry("hu", "hu"), Map.entry("hy", "hy"), Map.entry("id", "id"),
        Map.entry("it", "it"), Map.entry("ja", "ja"), Map.entry("ko", "cjk"), Map.entry("lv", "lv"),
        Map.entry("nl", "nl"), Map.entry("nb", "no"), Map.entry("nn", "no"), Map.entry("no", "no"),
        Map.entry("pt", "pt"), Map.entry("ro", "ro"), Map.entry("ru", "ru"), Map.entry("sv", "sv"),
        Map.entry("th", "th"), Map.entry("tr", "tr"), Map.entry("zh", "cjk"));

    private final FieldMappings mappings;

    /**
     * Creates the transformer from the configured mapping document.
     *
     * @param properties transform settings ({@code articulorium.transform.field-mappings})
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
        final Map<String, Set<Object>> byLanguage = new LinkedHashMap<>();
        final Set<String> languages = new TreeSet<>();
        final Resource subject = context.subject();
        for (final FieldMapping field : mappings.fields()) {
            final Set<Object> values = new LinkedHashSet<>();
            for (final List<String> path : field.paths()) {
                for (final RDFNode node : follow(context.model(), subject, path)) {
                    field.type().convert(node).ifPresent(values::add);
                    if (field.type() == FieldType.STRING) {
                        language(node).ifPresent(language -> {
                            languages.add(language);
                            solrSuffix(language).ifPresent(suffix -> byLanguage
                                .computeIfAbsent(field.name() + "_txt_" + suffix, key -> new LinkedHashSet<>())
                                .add(node.asLiteral().getLexicalForm()));
                        });
                    }
                }
            }
            if (!values.isEmpty()) {
                document.put(field.name(), new ArrayList<>(values));
            }
        }
        byLanguage.forEach((name, values) -> document.put(name, new ArrayList<>(values)));
        if (!languages.isEmpty()) {
            document.put(LANGUAGES_FIELD, new ArrayList<>(languages));
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
    /**
     * The language of a language-tagged literal.
     *
     * @param node the node
     * @return the BCP 47 tag, empty for other nodes
     */
    static Optional<String> language(final RDFNode node) {
        if (node == null || !node.isLiteral()) {
            return Optional.empty();
        }
        final String language = node.asLiteral().getLanguage();
        return language == null || language.isEmpty() ? Optional.empty()
            : Optional.of(Locale.forLanguageTag(language).toLanguageTag());
    }

    /**
     * The Solr {@code _default} dynamic field suffix of a language.
     *
     * @param tag a BCP 47 tag
     * @return for example {@code de} for {@code de-AT}, empty for languages without an analyzer
     *     (their texts are still in the language-neutral field)
     */
    static Optional<String> solrSuffix(final String tag) {
        return Optional.ofNullable(SOLR_LANGUAGES.get(Locale.forLanguageTag(tag).getLanguage()));
    }

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
