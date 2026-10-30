package com.articulorum.transform.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.Triple;
import com.articulorum.event.DocumentEvent;
import com.articulorum.event.EventAction;
import com.articulorum.event.EventDestinations;
import com.articulorum.event.ResourceEvent;
import com.articulorum.transform.config.TransformProperties;
import com.articulorum.transform.registry.TransformerRegistry;
import com.articulorum.transform.spi.TransformationContext;
import com.articulorum.transform.spi.TransformationException;
import com.articulorum.transform.spi.Transformer;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.RDFNode;
import org.apache.jena.vocabulary.DCTerms;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;

/**
 * Unit tests for {@link TransformService}.
 */
class TransformServiceTest {

    private static final String BASE = "http://localhost:9000";

    /** Records the contexts it saw and emits the subject's titles under a configurable field. */
    static final class Titles implements Transformer {

        private final String name;

        private final String field;

        private final List<TransformationContext> seen = new ArrayList<>();

        Titles(final String name, final String field) {
            this.name = name;
            this.field = field;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public String description() {
            return "titles";
        }

        @Override
        public boolean supports(final TransformationContext context) {
            return context.resource() == null || !context.resource().getPath().startsWith("skip");
        }

        @Override
        public Map<String, Collection<?>> transform(final TransformationContext context) {
            seen.add(context);
            return Map.of(field, context.model().listObjectsOfProperty(context.subject(), DCTerms.title)
                .mapWith(RDFNode::toString).toList());
        }
    }

    private Titles first;

    private Titles second;

    private TransformProperties properties;

    private TransformService service;

    @BeforeEach
    void setUp() {
        first = new Titles("first", "title");
        second = new Titles("second", "title");
        final StaticListableBeanFactory factory = new StaticListableBeanFactory();
        factory.addBean("first", first);
        factory.addBean("second", second);
        properties = new TransformProperties();
        properties.setTransformers(List.of("first", "second"));
        service = new TransformService(new TransformerRegistry(factory.getBeanProvider(Transformer.class), properties),
            properties);
    }

    private static ResourceEvent event(final String path, final EventAction action, final List<Triple> triples) {
        return new ResourceEvent(TransformServiceTest.class, "platform", EventDestinations.all(),
            new LdpResource(path, InteractionModel.RDF_SOURCE), action, BASE, triples);
    }

    /** Every active transformer runs, values are merged and the id is the absolute IRI. */
    @Test
    void mergesTheDocumentsOfActiveTransformers() {
        final Map<String, Collection<?>> document = service.transform(event("books/1", EventAction.CREATED,
            List.of(Triple.literal("/books/1", DCTerms.title.getURI(), "Moby Dick"))));

        assertThat(values(document.get(DocumentEvent.ID_FIELD))).containsExactly(BASE + "/books/1");
        assertThat(values(document.get("title"))).containsExactly("Moby Dick");
        assertThat(first.seen).hasSize(1);
        assertThat(second.seen).hasSize(1);
        assertThat(first.seen.get(0).iri()).isEqualTo(BASE + "/books/1");
    }

    /** Deletions carry only the identifier so the index can drop the document. */
    @Test
    void deletionsOnlyCarryTheIdentifier() {
        final Map<String, Collection<?>> document = service.transform(event("books/1", EventAction.DELETED, List.of()));

        assertThat(document).containsOnlyKeys(DocumentEvent.ID_FIELD);
        assertThat(first.seen).isEmpty();
    }

    /** Transformers that do not support a resource are skipped. */
    @Test
    void skipsUnsupportedResources() {
        service.transform(event("skip/1", EventAction.UPDATED, List.of()));

        assertThat(first.seen).isEmpty();
    }

    /** A configured base URI replaces the one the platform reported. */
    @Test
    void honoursTheConfiguredBase() {
        properties.setBaseUri("https://repo.example.org");

        final Map<String, Collection<?>> document = service.transform(event("books/1", EventAction.CREATED, List.of()));

        assertThat(values(document.get(DocumentEvent.ID_FIELD))).containsExactly("https://repo.example.org/books/1");
    }

    /** Events without a resource cannot be transformed. */
    @Test
    void rejectsEventsWithoutResource() {
        final ResourceEvent event = new ResourceEvent(this, "platform", EventDestinations.all(), null,
            EventAction.CREATED, BASE, List.of());

        assertThatThrownBy(() -> service.transform(event)).isInstanceOf(TransformationException.class);
    }

    /** Previews run one transformer on a posted model. */
    @Test
    void previewsOneTransformer() {
        final Model model = ModelFactory.createDefaultModel();
        model.createResource("urn:x").addProperty(DCTerms.title, "Preview");

        final Map<String, Collection<?>> document = service.preview("second", "urn:x", model);

        assertThat(values(document.get("title"))).containsExactly("Preview");
        assertThat(values(document.get(DocumentEvent.ID_FIELD))).containsExactly("urn:x");
        assertThat(first.seen).isEmpty();
    }

    /** Relative IRIs, blank nodes and typed or tagged literals become a proper Jena model. */
    @Test
    void buildsModelsFromEventTriples() {
        final Model model = TransformService.toModel(List.of(
            Triple.iri("/books/1", DCTerms.creator.getURI(), "/people/ishmael"),
            Triple.blankNode("/books/1", DCTerms.publisher.getURI(), "b0"),
            Triple.literal("_:b0", DCTerms.title.getURI(), "Harper", null, null),
            Triple.literal("/books/1", DCTerms.title.getURI(), "Moby Dick", null, "en"),
            Triple.literal("/books/1", DCTerms.extent.getURI(), "635", "http://www.w3.org/2001/XMLSchema#integer", null)),
            BASE);

        assertThat(model.size()).isEqualTo(5);
        assertThat(model.contains(model.createResource(BASE + "/books/1"), DCTerms.creator,
            model.createResource(BASE + "/people/ishmael"))).isTrue();
        assertThat(model.listObjectsOfProperty(DCTerms.publisher).next().asResource()
            .getProperty(DCTerms.title).getString()).isEqualTo("Harper");
        assertThat(model.contains(null, DCTerms.title, model.createLiteral("Moby Dick", "en"))).isTrue();
        assertThat(model.listObjectsOfProperty(DCTerms.extent).next().asLiteral().getInt()).isEqualTo(635);
    }

    /** Merging combines values without duplicates. */
    @Test
    void mergesWithoutDuplicates() {
        final Map<String, Collection<?>> target = new java.util.LinkedHashMap<>(Map.of("a", List.of(1, 2)));

        TransformService.merge(target, Map.of("a", List.of(2, 3), "b", List.of("x")));

        assertThat(values(target.get("a"))).containsExactly(1, 2, 3);
        assertThat(values(target.get("b"))).containsExactly("x");
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
