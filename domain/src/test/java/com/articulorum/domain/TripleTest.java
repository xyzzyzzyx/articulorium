package com.articulorum.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Triple}.
 */
class TripleTest {

    private static final String P = "http://example.org/p";

    @Test
    void factoriesSetObjectKind() {
        assertEquals(TermKind.IRI, Triple.iri("/", P, "/a").getObjectKind());
        assertEquals(TermKind.BLANK_NODE, Triple.blankNode("/", P, "b0").getObjectKind());
        assertEquals(TermKind.LITERAL, Triple.literal("/", P, "x").getObjectKind());
    }

    @Test
    void nullDatatypeAndLanguageAreStoredAsEmpty() {
        final Triple triple = Triple.literal("/", P, "x", null, null);
        assertEquals("", triple.getDatatype());
        assertEquals("", triple.getLanguage());
    }

    @Test
    void equalityCoversEveryField() {
        assertEquals(Triple.literal("/", P, "x"), Triple.literal("/", P, "x"));
        assertEquals(Triple.literal("/", P, "x").hashCode(), Triple.literal("/", P, "x").hashCode());
        assertNotEquals(Triple.literal("/", P, "x"), Triple.literal("/", P, "x", null, "en"));
        assertNotEquals(Triple.literal("/", P, "/a"), Triple.iri("/", P, "/a"));
    }

    @Test
    void detectsBlankSubjects() {
        assertTrue(Triple.literal("_:b0", P, "x").isBlankSubject());
        assertFalse(Triple.literal("/", P, "x").isBlankSubject());
    }

    @Test
    void matchesSubjectAndPredicate() {
        assertTrue(Triple.literal("/", P, "x").matches("/", P));
        assertFalse(Triple.literal("/", P, "x").matches("/a", P));
    }

    @Test
    void requiresMandatoryTerms() {
        assertThrows(NullPointerException.class, () -> Triple.iri(null, P, "/a"));
        assertThrows(NullPointerException.class, () -> Triple.iri("/", null, "/a"));
        assertThrows(NullPointerException.class, () -> Triple.iri("/", P, null));
    }

}
