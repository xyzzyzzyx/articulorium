package com.articulorum.transform.iiif;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.articulorum.domain.vocabulary.Articulorum;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.domain.vocabulary.Pcdm;
import com.articulorum.domain.vocabulary.Premis;
import com.articulorum.domain.vocabulary.Rdf;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.datatypes.xsd.XSDDatatype;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link IiifPresentationService} against a mocked platform holding a two-page book in a
 * collection.
 */
class IiifPresentationServiceTest {

    private static final String P = "http://localhost:8000/platform/";

    private static final String DC = "http://purl.org/dc/terms/";

    private final PublicContext context = new PublicContext("http://localhost:8000/transform/", P,
        "http://localhost:8000/ui/", "Bearer t");

    private IiifPlatformReader reader;

    private IiifPresentationService service;

    private Resource resource(final Model model, final String path, final String... types) {
        final Resource subject = model.createResource(P + path);
        for (final String type : types) {
            subject.addProperty(model.createProperty(Rdf.TYPE), model.createResource(type));
        }
        return subject;
    }

    private void serve(final String path, final Model model) {
        when(reader.rdf(eq(path), any())).thenReturn(Optional.of(model));
    }

    @BeforeEach
    void setUp() throws Exception {
        reader = mock(IiifPlatformReader.class);
        service = new IiifPresentationService(reader, new IiifProperties());

        final Model collection = ModelFactory.createDefaultModel();
        resource(collection, "c", Pcdm.COLLECTION, Articulorum.COLLECTION, Ldp.BASIC_CONTAINER)
            .addProperty(collection.createProperty(DC + "title"), "Rare books")
            .addProperty(collection.createProperty(Ldp.CONTAINS), collection.createResource(P + "c/book"));
        serve("c", collection);

        final Model book = ModelFactory.createDefaultModel();
        resource(book, "c/book", Pcdm.OBJECT, "http://purl.org/ontology/bibo/Book")
            .addProperty(book.createProperty(DC + "title"), "Moby Dick")
            .addProperty(book.createProperty(DC + "creator"), "Herman Melville")
            .addProperty(book.createProperty(DC + "description"), "A whale of a tale")
            .addProperty(book.createProperty(DC + "rights"), "Public domain")
            .addProperty(book.createProperty(DC + "license"), book.createResource("https://creativecommons.org/publicdomain/mark/1.0/"))
            .addProperty(book.createProperty(Articulorum.TOPOLOGY), "book")
            .addProperty(book.createProperty(Pcdm.HAS_MEMBER), book.createResource(P + "c/book/members/page-2"))
            .addProperty(book.createProperty(Pcdm.HAS_MEMBER), book.createResource(P + "c/book/members/page-1"))
            .addProperty(book.createProperty(Pcdm.HAS_FILE), book.createResource(P + "c/book/files/book.pdf"));
        serve("c/book", book);
        serve("c/book/files/book.pdf/description", file("c/book/files/book.pdf", "application/pdf", "book.pdf",
            Pcdm.ORIGINAL_FILE));

        for (int i = 1; i <= 2; i++) {
            final Model page = ModelFactory.createDefaultModel();
            resource(page, "c/book/members/page-" + i, Pcdm.OBJECT)
                .addProperty(page.createProperty(DC + "title"), "Page " + i)
                .addLiteral(page.createProperty("http://schema.org/position"),
                    page.createTypedLiteral(Integer.toString(i), XSDDatatype.XSDinteger))
                .addProperty(page.createProperty(Pcdm.HAS_FILE), page.createResource(P + "c/book/members/page-" + i + "/files/master.png"))
                .addProperty(page.createProperty(Pcdm.HAS_FILE), page.createResource(P + "c/book/members/page-" + i + "/files/ocr.txt"));
            serve("c/book/members/page-" + i, page);
            serve("c/book/members/page-" + i + "/files/master.png/description",
                file("c/book/members/page-" + i + "/files/master.png", "image/png", "master.png", Pcdm.ORIGINAL_FILE));
            serve("c/book/members/page-" + i + "/files/ocr.txt/description",
                file("c/book/members/page-" + i + "/files/ocr.txt", "text/plain", "ocr.txt", Pcdm.EXTRACTED_TEXT));
        }
        when(reader.head(anyString(), anyInt(), any())).thenReturn(Optional.of(ImageDimensionsTest.image("png", 800, 1200)));
    }

    private Model file(final String path, final String mime, final String name, final String use) {
        final Model model = ModelFactory.createDefaultModel();
        resource(model, path, Ldp.NON_RDF_SOURCE, Pcdm.FILE, use)
            .addProperty(model.createProperty(Premis.HAS_MIME_TYPE), mime)
            .addProperty(model.createProperty(Premis.FILENAME), name);
        return model;
    }

    @Test
    @SuppressWarnings("unchecked")
    void objectsBecomeManifestsWithOrderedCanvases() {
        final Map<String, Object> manifest = service.export("c/book", context);

        assertThat(manifest).containsEntry("@context", IiifPresentationService.CONTEXT)
            .containsEntry("id", "http://localhost:8000/transform/iiif/c/book")
            .containsEntry("type", "Manifest")
            .containsEntry("label", Map.of("none", List.of("Moby Dick")))
            .containsEntry("summary", Map.of("none", List.of("A whale of a tale")))
            .containsEntry("rights", "https://creativecommons.org/publicdomain/mark/1.0/");
        assertThat((List<Map<String, Object>>) manifest.get("metadata"))
            .anySatisfy(entry -> assertThat(entry).containsEntry("label", Map.of("en", List.of("Creator")))
                .containsEntry("value", Map.of("none", List.of("Herman Melville"))));
        assertThat((List<Map<String, Object>>) manifest.get("rendering"))
            .singleElement().satisfies(r -> assertThat(r).containsEntry("format", "application/pdf"));
        assertThat((List<Map<String, Object>>) manifest.get("homepage"))
            .singleElement().satisfies(h -> assertThat(h).containsEntry("id", "http://localhost:8000/ui/browse/c/book"));

        final List<Map<String, Object>> canvases = (List<Map<String, Object>>) manifest.get("items");
        assertThat(canvases).hasSize(2);
        assertThat(canvases.get(0)).containsEntry("label", Map.of("none", List.of("Page 1")))
            .containsEntry("width", 800).containsEntry("height", 1200);
        final Map<String, Object> page = ((List<Map<String, Object>>) canvases.get(0).get("items")).get(0);
        final Map<String, Object> annotation = ((List<Map<String, Object>>) page.get("items")).get(0);
        assertThat(annotation).containsEntry("motivation", "painting")
            .containsEntry("target", canvases.get(0).get("id"));
        assertThat((Map<String, Object>) annotation.get("body"))
            .containsEntry("id", P + "c/book/members/page-1/files/master.png")
            .containsEntry("type", "Image").containsEntry("format", "image/png");
        assertThat((List<Map<String, Object>>) canvases.get(0).get("rendering"))
            .singleElement().satisfies(r -> assertThat(r).containsEntry("type", "Text"));
        assertThat((List<Map<String, Object>>) manifest.get("thumbnail")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void collectionsListTheirObjects() {
        final Map<String, Object> collection = service.export("c", context);

        assertThat(collection).containsEntry("type", "Collection");
        assertThat((List<Map<String, Object>>) collection.get("items")).singleElement().satisfies(item ->
            assertThat(item).containsEntry("id", "http://localhost:8000/transform/iiif/c/book")
                .containsEntry("type", "Manifest"));
    }

    @Test
    void otherResourcesCannotBeExported() {
        final Model plain = ModelFactory.createDefaultModel();
        resource(plain, "plain", Ldp.RDF_SOURCE);
        serve("plain", plain);
        when(reader.rdf(eq("missing"), any())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.export("plain", context))
            .isInstanceOfSatisfying(IiifException.class, e -> assertThat(e.getStatus()).isEqualTo(422));
        assertThatThrownBy(() -> service.export("missing", context))
            .isInstanceOfSatisfying(IiifException.class, e -> assertThat(e.getStatus()).isEqualTo(404));
    }

    @Test
    void helpersFormatLabelsAndDurations() {
        assertThat(IiifPresentationService.humanize("pageStart")).isEqualTo("Page start");
        assertThat(IiifPresentationService.humanize("isbn13")).isEqualTo("Isbn13");
        assertThat(IiifPresentationService.humanize("ServiceFile")).isEqualTo("Service file");
    }

}
