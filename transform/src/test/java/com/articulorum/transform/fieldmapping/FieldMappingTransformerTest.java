package com.articulorum.transform.fieldmapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.articulorum.transform.config.TransformProperties;
import com.articulorum.transform.spi.TransformationContext;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.vocabulary.DCTerms;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.DefaultResourceLoader;

/**
 * Unit tests for {@link FieldMappingTransformer}.
 */
class FieldMappingTransformerTest {

    private static final String IRI = "http://localhost:9000/books/1";

    private static final String EX = "http://example.org/";

    private final FieldMappingTransformer transformer = new FieldMappingTransformer(new FieldMappings(
        Map.of(),
        List.of(
            new FieldMapping("title", List.of(List.of(DCTerms.title.getURI()), List.of(DCTerms.alternative.getURI())),
                FieldType.STRING),
            new FieldMapping("authorName", List.of(List.of(EX + "author", EX + "name")), FieldType.STRING),
            new FieldMapping("pages", List.of(List.of(EX + "pages")), FieldType.INTEGER),
            new FieldMapping("missing", List.of(List.of(EX + "nothing")), FieldType.STRING))));

    /** Values from alternative and multi-step paths are collected; empty fields are omitted. */
    @Test
    void mapsPropertiesToFields() {
        final Model model = ModelFactory.createDefaultModel();
        final Resource book = model.createResource(IRI);
        final Resource author = model.createResource();
        book.addProperty(DCTerms.title, "Moby Dick")
            .addProperty(DCTerms.alternative, "The Whale")
            .addProperty(DCTerms.alternative, "Moby Dick")
            .addProperty(model.createProperty(EX + "author"), author)
            .addProperty(model.createProperty(EX + "pages"), "635");
        author.addProperty(model.createProperty(EX + "name"), "Herman Melville");

        final Map<String, Collection<?>> document = transformer.transform(new TransformationContext(IRI, null, model));

        assertThat(document).containsOnlyKeys("title", "authorName", "pages");
        assertThat(values(document.get("title"))).containsExactly("Moby Dick", "The Whale");
        assertThat(values(document.get("authorName"))).containsExactly("Herman Melville");
        assertThat(values(document.get("pages"))).containsExactly(635L);
    }

    /** Paths do not continue through literals. */
    @Test
    void stopsAtLiterals() {
        final Model model = ModelFactory.createDefaultModel();
        model.createResource(IRI).addProperty(model.createProperty(EX + "author"), "just a name");

        assertThat(FieldMappingTransformer.follow(model, model.createResource(IRI), List.of(EX + "author", EX + "name")))
            .isEmpty();
    }

    /** The Spring constructor loads the configured mapping file. */
    @Test
    void loadsConfiguredMappings() throws Exception {
        final FieldMappingTransformer loaded = new FieldMappingTransformer(new TransformProperties(),
            new DefaultResourceLoader());

        assertThat(loaded.name()).isEqualTo(FieldMappingTransformer.NAME);
        assertThat(loaded.mappings().fields()).isNotEmpty();
        assertThat(loaded.description()).contains(loaded.mappings().fields().size() + " fields");
    }

    /**
     * Copies field values into a list AssertJ can compare element by element.
     *
     * @param values the values of a document field
     * @return the values
     */
    private static List<Object> values(final Collection<?> values) {
        return new java.util.ArrayList<>(values);
    }

}
