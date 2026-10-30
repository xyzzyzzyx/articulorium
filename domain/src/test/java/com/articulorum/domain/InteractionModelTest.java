package com.articulorum.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import com.articulorum.domain.vocabulary.Ldp;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link InteractionModel}.
 */
class InteractionModelTest {

    @Test
    void containersAreRdfSources() {
        for (final InteractionModel model : List.of(InteractionModel.BASIC_CONTAINER,
                InteractionModel.DIRECT_CONTAINER, InteractionModel.INDIRECT_CONTAINER)) {
            assertTrue(model.isContainer());
            assertTrue(model.isRdfSource());
        }
        assertFalse(InteractionModel.RDF_SOURCE.isContainer());
        assertFalse(InteractionModel.NON_RDF_SOURCE.isContainer());
        assertFalse(InteractionModel.NON_RDF_SOURCE.isRdfSource());
    }

    @Test
    void onlyDirectAndIndirectContainersMaintainMembership() {
        assertTrue(InteractionModel.DIRECT_CONTAINER.isMembershipContainer());
        assertTrue(InteractionModel.INDIRECT_CONTAINER.isMembershipContainer());
        assertFalse(InteractionModel.BASIC_CONTAINER.isMembershipContainer());
    }

    @Test
    void typeIrisListEveryLdpClass() {
        assertEquals(List.of(Ldp.RESOURCE, Ldp.RDF_SOURCE), InteractionModel.RDF_SOURCE.getTypeIris());
        assertEquals(List.of(Ldp.RESOURCE, Ldp.RDF_SOURCE, Ldp.CONTAINER, Ldp.DIRECT_CONTAINER),
            InteractionModel.DIRECT_CONTAINER.getTypeIris());
        assertEquals(List.of(Ldp.RESOURCE, Ldp.NON_RDF_SOURCE), InteractionModel.NON_RDF_SOURCE.getTypeIris());
    }

    @Test
    void fromIriResolvesKnownClasses() {
        assertEquals(Optional.of(InteractionModel.INDIRECT_CONTAINER), InteractionModel.fromIri(Ldp.INDIRECT_CONTAINER));
        assertEquals(Optional.of(InteractionModel.BASIC_CONTAINER), InteractionModel.fromIri(Ldp.CONTAINER));
        assertEquals(Optional.of(InteractionModel.NON_RDF_SOURCE), InteractionModel.fromIri(Ldp.NON_RDF_SOURCE));
    }

    @Test
    void fromIriIgnoresUnknownClasses() {
        assertEquals(Optional.empty(), InteractionModel.fromIri(Ldp.RESOURCE));
        assertEquals(Optional.empty(), InteractionModel.fromIri("http://example.org/Other"));
        assertEquals(Optional.empty(), InteractionModel.fromIri(null));
    }

}
