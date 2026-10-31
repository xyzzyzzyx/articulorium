package com.articulorum.domain.rdf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ResourcePaths}.
 */
class ResourcePathsTest {

    @Test
    void normalizeRemovesLeadingTrailingAndRepeatedSlashes() {
        assertEquals("", ResourcePaths.normalize(null));
        assertEquals("", ResourcePaths.normalize("/"));
        assertEquals("", ResourcePaths.normalize("///"));
        assertEquals("a", ResourcePaths.normalize("/a/"));
        assertEquals("a/b/c", ResourcePaths.normalize("//a//b/c/"));
    }

    @Test
    void parentOfRootIsEmpty() {
        assertEquals(Optional.empty(), ResourcePaths.parentOf(""));
    }

    @Test
    void parentOfTopLevelIsRoot() {
        assertEquals(Optional.of(""), ResourcePaths.parentOf("books"));
    }

    @Test
    void parentOfNestedPath() {
        assertEquals(Optional.of("books/1"), ResourcePaths.parentOf("/books/1/cover/"));
    }

    @Test
    void nameOfReturnsLastSegment() {
        assertEquals("", ResourcePaths.nameOf(""));
        assertEquals("books", ResourcePaths.nameOf("books"));
        assertEquals("cover", ResourcePaths.nameOf("books/1/cover"));
    }

    @Test
    void childJoinsSegments() {
        assertEquals("books", ResourcePaths.child("", "books"));
        assertEquals("books/1", ResourcePaths.child("books", "1"));
    }

    @Test
    void childRejectsInvalidSegments() {
        assertThrows(IllegalArgumentException.class, () -> ResourcePaths.child("books", ""));
        assertThrows(IllegalArgumentException.class, () -> ResourcePaths.child("books", "a/b"));
    }

    @Test
    void relativeIriRoundTrip() {
        assertEquals("/", ResourcePaths.toRelativeIri(""));
        assertEquals("/books/1", ResourcePaths.toRelativeIri("books/1"));
        assertEquals(Optional.of(""), ResourcePaths.fromRelativeIri("/"));
        assertEquals(Optional.of("books/1"), ResourcePaths.fromRelativeIri("/books/1"));
    }

    @Test
    void fromRelativeIriDropsFragmentAndQuery() {
        assertEquals(Optional.of("books/1"), ResourcePaths.fromRelativeIri("/books/1#it"));
        assertEquals(Optional.of("books/1"), ResourcePaths.fromRelativeIri("/books/1?x=y"));
    }

    @Test
    void fromRelativeIriRejectsAbsoluteIris() {
        assertEquals(Optional.empty(), ResourcePaths.fromRelativeIri("http://example.org/books"));
        assertEquals(Optional.empty(), ResourcePaths.fromRelativeIri(null));
    }

    @Test
    void isSelfOrDescendant() {
        assertTrue(ResourcePaths.isSelfOrDescendant("books/1", ""));
        assertTrue(ResourcePaths.isSelfOrDescendant("books", "books"));
        assertTrue(ResourcePaths.isSelfOrDescendant("books/1", "books"));
        assertFalse(ResourcePaths.isSelfOrDescendant("bookshelf", "books"));
        assertFalse(ResourcePaths.isSelfOrDescendant("books", "books/1"));
    }

}
