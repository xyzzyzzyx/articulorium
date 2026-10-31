package com.articulorum.platform.rdf;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.StringReader;
import java.util.List;

import com.articulorum.domain.TermKind;
import com.articulorum.domain.Triple;
import com.articulorum.domain.vocabulary.Rdf;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TripleMapper}.
 */
class TripleMapperTest {

    private static final String BASE = "http://localhost:9000";

    private static final String TURTLE = """
        @prefix dcterms: <http://purl.org/dc/terms/> .
        @prefix xsd: <http://www.w3.org/2001/XMLSchema#> .
        <http://localhost:9000/books/1>
            dcterms:title "Moby Dick" , "Moby-Dick"@en ;
            dcterms:extent "635"^^xsd:int ;
            dcterms:creator [ dcterms:title "Herman Melville" ] ;
            dcterms:isPartOf <http://localhost:9000/books> ;
            dcterms:source <http://example.org/gutenberg/2701> .
        """;

    private Model parse() {
        final Model model = ModelFactory.createDefaultModel();
        model.read(new StringReader(TURTLE), BASE, "TURTLE");
        return model;
    }

    @Test
    void relativizesServerIrisAndKeepsForeignOnes() {
        final List<Triple> triples = TripleMapper.toTriples(parse(), BASE);
        assertThat(triples).extracting(Triple::getSubject).contains("/books/1");
        assertThat(triples).contains(Triple.iri("/books/1", "http://purl.org/dc/terms/isPartOf", "/books"));
        assertThat(triples).contains(Triple.iri("/books/1", "http://purl.org/dc/terms/source",
            "http://example.org/gutenberg/2701"));
    }

    @Test
    void mapsLiteralDatatypesAndLanguages() {
        final List<Triple> triples = TripleMapper.toTriples(parse(), BASE);
        assertThat(triples).contains(Triple.literal("/books/1", "http://purl.org/dc/terms/title", "Moby Dick"));
        assertThat(triples).contains(Triple.literal("/books/1", "http://purl.org/dc/terms/title", "Moby-Dick", null, "en"));
        assertThat(triples).contains(Triple.literal("/books/1", "http://purl.org/dc/terms/extent", "635",
            Rdf.XSD_NAMESPACE + "int", null));
    }

    @Test
    void mapsBlankNodesConsistently() {
        final List<Triple> triples = TripleMapper.toTriples(parse(), BASE);
        final Triple creator = triples.stream().filter(t -> t.getPredicate().endsWith("creator")).findFirst().orElseThrow();
        assertThat(creator.getObjectKind()).isEqualTo(TermKind.BLANK_NODE);
        assertThat(triples).anyMatch(t -> t.getSubject().equals(Triple.BLANK_NODE_PREFIX + creator.getObject()));
    }

    @Test
    void roundTripIsLossless() {
        final Model original = parse();
        final Model copy = TripleMapper.toModel(TripleMapper.toTriples(original, BASE), BASE);
        assertThat(copy.isIsomorphicWith(original)).isTrue();
    }

    @Test
    void roundTripUnderAnotherBaseMovesServerIris() {
        final Model moved = TripleMapper.toModel(TripleMapper.toTriples(parse(), BASE), "https://example.com/platform");
        assertThat(moved.containsResource(moved.createResource("https://example.com/platform/books/1"))).isTrue();
    }

}
