package com.articulorium.oai.metadata;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import com.articulorium.oai.config.OaiProperties;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link DublinCoreMapper}.
 */
class DublinCoreMapperTest {

    private static final String IRI = "http://localhost:8000/platform/books/moby";

    private static final String DCTERMS = "http://purl.org/dc/terms/";

    @Test
    void mapsDescriptivePropertiesToDublinCoreInElementOrder() {
        final Model model = ModelFactory.createDefaultModel();
        final Resource book = model.createResource(IRI);
        book.addProperty(model.createProperty(DCTERMS + "title"), "Moby-Dick", "en");
        book.addProperty(model.createProperty(DCTERMS + "creator"), "Melville, Herman");
        book.addProperty(model.createProperty(DCTERMS + "subject"), "Whaling");
        book.addProperty(model.createProperty(DCTERMS + "subject"), "Sea stories");
        book.addProperty(model.createProperty(DCTERMS + "date"), "1851");
        book.addProperty(model.createProperty(DCTERMS + "license"),
            model.createResource("https://creativecommons.org/publicdomain/mark/1.0/"));
        // a blank node and an unmapped property are left out
        book.addProperty(model.createProperty(DCTERMS + "publisher"), model.createResource());
        book.addProperty(model.createProperty("http://example.org/shelf"), "B3");
        // another subject's statements are not the record's
        model.createResource("http://localhost:8000/platform/books").addProperty(
            model.createProperty(DCTERMS + "title"), "Books");

        final List<DcValue> values = new DublinCoreMapper(new OaiProperties()).map(model, IRI,
            "http://localhost:8000/ui/browse/books/moby");

        assertThat(values).containsExactly(
            new DcValue("title", "Moby-Dick", "en"),
            new DcValue("creator", "Melville, Herman", null),
            new DcValue("subject", "Sea stories", null),
            new DcValue("subject", "Whaling", null),
            new DcValue("date", "1851", null),
            new DcValue("identifier", "http://localhost:8000/ui/browse/books/moby", null),
            new DcValue("identifier", IRI, null),
            new DcValue("rights", "https://creativecommons.org/publicdomain/mark/1.0/", null));
    }

    @Test
    void readsContainersDescribedWithATrailingSlashAndDropsDuplicates() {
        final Model model = ModelFactory.createDefaultModel();
        model.createResource(IRI + "/").addProperty(model.createProperty(DCTERMS + "title"), "Moby-Dick")
            .addProperty(model.createProperty("http://purl.org/dc/elements/1.1/title"), "Moby-Dick");

        assertThat(new DublinCoreMapper(new OaiProperties()).map(model, IRI, null))
            .containsExactly(new DcValue("title", "Moby-Dick", null), new DcValue("identifier", IRI, null));
    }

    @Test
    void followsConfiguredMappingsAndRejectsUnknownElements() {
        final OaiProperties properties = new OaiProperties();
        properties.getDublinCore().put("title", List.of("http://example.org/label"));
        properties.getDublinCore().put("subject", List.of());
        final Model model = ModelFactory.createDefaultModel();
        model.createResource(IRI)
            .addProperty(model.createProperty("http://example.org/label"), "Label")
            .addProperty(model.createProperty(DCTERMS + "title"), "Ignored")
            .addProperty(model.createProperty(DCTERMS + "subject"), "Ignored too");

        assertThat(new DublinCoreMapper(properties).map(model, IRI, null))
            .containsExactly(new DcValue("title", "Label", null), new DcValue("identifier", IRI, null));

        properties.getDublinCore().put("colour", List.of(DCTERMS + "title"));
        assertThatThrownBy(() -> new DublinCoreMapper(properties)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("colour");
    }

}
