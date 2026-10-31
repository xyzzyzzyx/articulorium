package com.articulorum.domain.rdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Iris}.
 */
class IrisTest {

    private static final String BASE = "http://localhost:9000/platform";

    @Test
    void normalizeBaseRemovesTrailingSlashes() {
        assertEquals(BASE, Iris.normalizeBase(BASE + "//"));
        assertEquals("", Iris.normalizeBase(null));
    }

    @Test
    void relativizeRootIri() {
        assertEquals("/", Iris.relativize(BASE, BASE));
        assertEquals("/", Iris.relativize(BASE, BASE + "/"));
        assertEquals("/", Iris.relativize(BASE + "/", BASE + "/"));
    }

    @Test
    void relativizeServerResources() {
        assertEquals("/books/1", Iris.relativize(BASE, BASE + "/books/1"));
        assertEquals("/books/1#it", Iris.relativize(BASE, BASE + "/books/1#it"));
        assertEquals("/#it", Iris.relativize(BASE, BASE + "#it"));
    }

    @Test
    void relativizeLeavesForeignIrisUntouched() {
        assertEquals("http://example.org/a", Iris.relativize(BASE, "http://example.org/a"));
        assertEquals(BASE + "2/x", Iris.relativize(BASE, BASE + "2/x"));
    }

    @Test
    void resolveRelativeIris() {
        assertEquals(BASE + "/", Iris.resolve(BASE, "/"));
        assertEquals(BASE + "/books/1", Iris.resolve(BASE + "/", "/books/1"));
    }

    @Test
    void resolveLeavesAbsoluteIrisUntouched() {
        assertEquals("urn:uuid:1234", Iris.resolve(BASE, "urn:uuid:1234"));
    }

    @Test
    void relativizeAndResolveAreInverse() {
        final String iri = BASE + "/a/b#c";
        assertEquals(iri, Iris.resolve(BASE, Iris.relativize(BASE, iri)));
    }

    @Test
    void detectsRelativeAndAbsoluteIris() {
        assertTrue(Iris.isRelative("/a"));
        assertFalse(Iris.isRelative("a"));
        assertTrue(Iris.isAbsolute("http://example.org/"));
        assertTrue(Iris.isAbsolute("urn:x"));
        assertFalse(Iris.isAbsolute("/a"));
        assertFalse(Iris.isAbsolute("1abc:def"));
    }

}
