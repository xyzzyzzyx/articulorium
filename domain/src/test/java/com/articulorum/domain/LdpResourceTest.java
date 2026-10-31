package com.articulorum.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LdpResource}.
 */
class LdpResourceTest {

    private static final String P = "http://example.org/p";

    @Test
    void constructorNormalisesPathAndDerivesParent() {
        final LdpResource resource = new LdpResource("/books/1/", InteractionModel.RDF_SOURCE);
        assertNotNull(resource.getId());
        assertEquals("books/1", resource.getPath());
        assertEquals("books", resource.getParentPath());
        assertEquals("/books/1", resource.getRelativeIri());
        assertEquals(resource.getCreated(), resource.getModified());
    }

    @Test
    void rootHasNoParent() {
        final LdpResource root = new LdpResource("", InteractionModel.BASIC_CONTAINER);
        assertTrue(root.isRoot());
        assertNull(root.getParentPath());
        assertEquals("/", root.getRelativeIri());
        assertTrue(root.isContainer());
    }

    @Test
    void setTriplesRemovesDuplicatesAndKeepsOrder() {
        final LdpResource resource = new LdpResource("a", InteractionModel.RDF_SOURCE);
        resource.setTriples(List.of(Triple.literal("/a", P, "1"), Triple.literal("/a", P, "2"),
            Triple.literal("/a", P, "1")));
        assertEquals(List.of(Triple.literal("/a", P, "1"), Triple.literal("/a", P, "2")), resource.getTriples());
    }

    @Test
    void touchAlwaysAdvancesModificationTime() {
        final LdpResource resource = new LdpResource("a", InteractionModel.RDF_SOURCE);
        final var before = resource.getModified();
        resource.touch();
        assertTrue(resource.getModified().isAfter(before));
    }

    @Test
    void createdByRecordsCreatorAndLastModifier() {
        final LdpResource resource = new LdpResource("a", InteractionModel.RDF_SOURCE).createdBy("alice");
        assertEquals("alice", resource.getCreatedBy());
        assertEquals("alice", resource.getLastModifiedBy());
    }

    @Test
    void touchByAgentRecordsLastModifier() {
        final LdpResource resource = new LdpResource("a", InteractionModel.RDF_SOURCE).createdBy("alice");
        final var before = resource.getModified();
        resource.touch("bob");
        assertEquals("alice", resource.getCreatedBy());
        assertEquals("bob", resource.getLastModifiedBy());
        assertTrue(resource.getModified().isAfter(before));
        resource.touch((String) null);
        assertEquals("bob", resource.getLastModifiedBy());
    }

    @Test
    void versionedDefaultsToFalse() {
        final LdpResource resource = new LdpResource("a", InteractionModel.RDF_SOURCE);
        assertFalse(resource.isVersioned());
        resource.setVersioned(true);
        assertTrue(resource.isVersioned());
    }

    @Test
    void binaryContentRecordsStorageVersion() {
        final BinaryContent content = new BinaryContent("info:articulorum/a", "v2", "text/plain", "a.txt", 3, "abc");
        assertEquals("info:articulorum/a", content.getStorageKey());
        assertEquals("v2", content.getStorageVersion());
        assertNotEquals(content, new BinaryContent("info:articulorum/a", "v3", "text/plain", "a.txt", 3, "abc"));
        assertEquals(content, new BinaryContent("info:articulorum/a", "v2", "text/plain", "a.txt", 3, "abc"));
    }

    @Test
    void identityIsBasedOnId() {
        final LdpResource a = new LdpResource("a", InteractionModel.RDF_SOURCE);
        final LdpResource copy = new LdpResource("b", InteractionModel.BASIC_CONTAINER);
        copy.setId(a.getId());
        assertEquals(a, copy);
        assertNotEquals(a, new LdpResource("a", InteractionModel.RDF_SOURCE));
    }

    @Test
    void nonRdfSourceIsDetected() {
        assertTrue(new LdpResource("f", InteractionModel.NON_RDF_SOURCE).isNonRdfSource());
        assertFalse(new LdpResource("f", InteractionModel.RDF_SOURCE).isNonRdfSource());
    }

}
