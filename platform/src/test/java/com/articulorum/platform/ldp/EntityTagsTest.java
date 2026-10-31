package com.articulorum.platform.ldp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EntityTags}.
 */
class EntityTagsTest {

    @Test
    void tagIsQuotedAndChangesWithVersion() {
        final LdpResource resource = new LdpResource("a", InteractionModel.RDF_SOURCE);
        resource.setVersion(3L);
        final String tag = EntityTags.of(resource);
        assertEquals("\"" + resource.getId() + "-3\"", tag);
        resource.setVersion(4L);
        assertNotEquals(tag, EntityTags.of(resource));
    }

    @Test
    void unversionedResourceUsesZero() {
        final LdpResource resource = new LdpResource("a", InteractionModel.RDF_SOURCE);
        assertTrue(EntityTags.of(resource).endsWith("-0\""));
    }

    @Test
    void matchesExactListedAndWildcardTags() {
        assertTrue(EntityTags.matches("\"a\"", "\"a\""));
        assertTrue(EntityTags.matches("\"x\", \"a\"", "\"a\""));
        assertTrue(EntityTags.matches("*", "\"a\""));
        assertTrue(EntityTags.matches("W/\"a\"", "\"a\""));
    }

    @Test
    void rejectsOtherTagsAndAbsentHeader() {
        assertFalse(EntityTags.matches("\"b\"", "\"a\""));
        assertFalse(EntityTags.matches(null, "\"a\""));
    }

}
