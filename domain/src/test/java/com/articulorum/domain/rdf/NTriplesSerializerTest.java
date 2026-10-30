package com.articulorum.domain.rdf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import com.articulorum.domain.Triple;
import com.articulorum.domain.vocabulary.Rdf;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NTriplesSerializer}.
 */
class NTriplesSerializerTest {

    private static final String BASE = "http://localhost:9000";

    private static final String TITLE = "http://purl.org/dc/terms/title";

    @Test
    void serializesIriObjectAndResolvesRelativeIris() {
        final Triple triple = Triple.iri("/books", Rdf.TYPE, "http://example.org/Shelf");
        assertEquals("<http://localhost:9000/books> <" + Rdf.TYPE + "> <http://example.org/Shelf> .",
            NTriplesSerializer.serialize(triple, BASE));
    }

    @Test
    void serializesPlainLiteralWithoutDatatype() {
        final Triple triple = Triple.literal("/", TITLE, "Root", Rdf.XSD_STRING, null);
        assertEquals("<http://localhost:9000/> <" + TITLE + "> \"Root\" .",
            NTriplesSerializer.serialize(triple, BASE));
    }

    @Test
    void serializesTypedAndLanguageLiterals() {
        assertEquals("\"5\"^^<http://www.w3.org/2001/XMLSchema#int>",
            NTriplesSerializer.literal("5", "http://www.w3.org/2001/XMLSchema#int", ""));
        assertEquals("\"chat\"@fr", NTriplesSerializer.literal("chat", Rdf.LANG_STRING, "fr"));
    }

    @Test
    void escapesLiteralCharacters() {
        assertEquals("a\\\"b\\\\c\\nd\\te\\u0001", NTriplesSerializer.escapeString("a\"b\\c\nd\te\u0001"));
    }

    @Test
    void escapesIriCharacters() {
        assertEquals("<http://x/a\\u0020b\\u003E>", NTriplesSerializer.iri("http://x/a b>"));
    }

    @Test
    void serializesBlankNodes() {
        final Triple triple = Triple.blankNode("_:a.b", TITLE, "c d");
        assertEquals("_:a_b <" + TITLE + "> _:c_d .", NTriplesSerializer.serialize(triple, BASE));
        assertEquals("_:b", NTriplesSerializer.blankNode(""));
    }

    @Test
    void serializesDocumentOneStatementPerLine() {
        final String document = NTriplesSerializer.serialize(List.of(
            Triple.literal("/", TITLE, "a"),
            Triple.literal("/", TITLE, "b")), BASE);
        assertEquals(2, document.split("\n").length);
    }

}
