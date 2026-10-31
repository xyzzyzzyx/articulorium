package com.articulorum.platform.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.articulorum.domain.BinaryContent;
import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.Triple;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.domain.vocabulary.Premis;
import com.articulorum.domain.vocabulary.Rdf;
import com.articulorum.platform.ldp.exception.ConstraintViolationException;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LdpRules}.
 */
class LdpRulesTest {

    private static final String TITLE = "http://purl.org/dc/terms/title";

    private static final String HAS_MEMBER = "http://pcdm.org/models#hasMember";

    private static final String PROXY_FOR = "http://www.openarchives.org/ore/terms/proxyFor";

    // -- inferModel -----------------------------------------------------------------------------

    @Test
    void plainRdfBodyCreatesBasicContainer() {
        assertEquals(InteractionModel.BASIC_CONTAINER,
            LdpRules.inferModel("/a", List.of(Triple.literal("/a", TITLE, "A"))));
        assertEquals(InteractionModel.BASIC_CONTAINER, LdpRules.inferModel("/a", List.of()));
    }

    @Test
    void explicitLdpTypeWins() {
        assertEquals(InteractionModel.RDF_SOURCE,
            LdpRules.inferModel("/a", List.of(Triple.iri("/a", Rdf.TYPE, Ldp.RDF_SOURCE))));
        assertEquals(InteractionModel.DIRECT_CONTAINER,
            LdpRules.inferModel("/a", List.of(Triple.iri("/a", Rdf.TYPE, Ldp.RDF_SOURCE),
                Triple.iri("/a", Rdf.TYPE, Ldp.DIRECT_CONTAINER))));
        assertEquals(InteractionModel.BASIC_CONTAINER,
            LdpRules.inferModel("/a", List.of(Triple.iri("/a", Rdf.TYPE, Ldp.CONTAINER))));
    }

    @Test
    void membershipTriplesCreateDirectOrIndirectContainers() {
        final List<Triple> direct = List.of(Triple.iri("/a", Ldp.MEMBERSHIP_RESOURCE, "/b"),
            Triple.iri("/a", Ldp.HAS_MEMBER_RELATION, HAS_MEMBER));
        assertEquals(InteractionModel.DIRECT_CONTAINER, LdpRules.inferModel("/a", direct));
        final List<Triple> indirect = List.of(Triple.iri("/a", Ldp.MEMBERSHIP_RESOURCE, "/b"),
            Triple.iri("/a", Ldp.HAS_MEMBER_RELATION, HAS_MEMBER),
            Triple.iri("/a", Ldp.INSERTED_CONTENT_RELATION, PROXY_FOR));
        assertEquals(InteractionModel.INDIRECT_CONTAINER, LdpRules.inferModel("/a", indirect));
    }

    @Test
    void triplesAboutOtherSubjectsDoNotInfluenceTheModel() {
        assertEquals(InteractionModel.BASIC_CONTAINER,
            LdpRules.inferModel("/a", List.of(Triple.iri("/a#it", Rdf.TYPE, Ldp.DIRECT_CONTAINER))));
    }

    // -- acceptClientTriples --------------------------------------------------------------------

    @Test
    void keepsClientTriples() {
        final LdpResource resource = new LdpResource("a", InteractionModel.BASIC_CONTAINER);
        final List<Triple> incoming = List.of(Triple.literal("/a", TITLE, "A"), Triple.literal("/a#x", TITLE, "X"));
        assertEquals(incoming, LdpRules.acceptClientTriples(resource, incoming, List.of()));
    }

    @Test
    void dropsEchoedServerManagedTriplesAndMatchingTypes() {
        final LdpResource resource = new LdpResource("a", InteractionModel.BASIC_CONTAINER);
        final Triple contains = Triple.iri("/a", Ldp.CONTAINS, "/a/b");
        final List<Triple> incoming = List.of(contains, Triple.iri("/a", Rdf.TYPE, Ldp.CONTAINER),
            Triple.iri("/a", Rdf.TYPE, Ldp.BASIC_CONTAINER), Triple.literal("/a", TITLE, "A"));
        assertEquals(List.of(Triple.literal("/a", TITLE, "A")),
            LdpRules.acceptClientTriples(resource, incoming, List.of(contains)));
    }

    @Test
    void keepsNonLdpTypes() {
        final LdpResource resource = new LdpResource("a", InteractionModel.RDF_SOURCE);
        final Triple type = Triple.iri("/a", Rdf.TYPE, "http://pcdm.org/models#Object");
        assertEquals(List.of(type), LdpRules.acceptClientTriples(resource, List.of(type), List.of()));
    }

    @Test
    void rejectsNewContainmentTriples() {
        final LdpResource resource = new LdpResource("a", InteractionModel.BASIC_CONTAINER);
        assertThrows(ConstraintViolationException.class, () -> LdpRules.acceptClientTriples(resource,
            List.of(Triple.iri("/a", Ldp.CONTAINS, "/elsewhere")), List.of()));
    }

    @Test
    void rejectsInteractionModelChanges() {
        final LdpResource resource = new LdpResource("a", InteractionModel.RDF_SOURCE);
        assertThrows(ConstraintViolationException.class, () -> LdpRules.acceptClientTriples(resource,
            List.of(Triple.iri("/a", Rdf.TYPE, Ldp.BASIC_CONTAINER)), List.of()));
    }

    @Test
    void rejectsTechnicalMetadataOfBinaries() {
        final LdpResource resource = new LdpResource("f", InteractionModel.NON_RDF_SOURCE);
        resource.setBinary(new BinaryContent("k", "v1", "text/plain", "f.txt", 3, "abc"));
        assertThrows(ConstraintViolationException.class, () -> LdpRules.acceptClientTriples(resource,
            List.of(Triple.literal("/f", Premis.HAS_MIME_TYPE, "image/png")), List.of()));
        final Triple echoed = Triple.literal("/f", Premis.HAS_MIME_TYPE, "text/plain");
        assertEquals(List.of(), LdpRules.acceptClientTriples(resource, List.of(echoed), List.of(echoed)));
    }

    // -- configureMembership --------------------------------------------------------------------

    @Test
    void basicContainersHaveNoMembership() {
        final LdpResource resource = new LdpResource("a", InteractionModel.BASIC_CONTAINER);
        resource.setTriples(List.of(Triple.iri("/a", Ldp.MEMBERSHIP_RESOURCE, "/b")));
        LdpRules.configureMembership(resource);
        assertNull(resource.getMembershipResource());
        assertNull(resource.getHasMemberRelation());
    }

    @Test
    void directContainerDefaultsToItselfAndLdpMember() {
        final LdpResource resource = new LdpResource("a", InteractionModel.DIRECT_CONTAINER);
        LdpRules.configureMembership(resource);
        assertEquals("/a", resource.getMembershipResource());
        assertEquals(Ldp.MEMBER, resource.getHasMemberRelation());
        assertTrue(resource.getTriples().contains(Triple.iri("/a", Ldp.MEMBERSHIP_RESOURCE, "/a")));
        assertTrue(resource.getTriples().contains(Triple.iri("/a", Ldp.HAS_MEMBER_RELATION, Ldp.MEMBER)));
    }

    @Test
    void directContainerUsesDeclaredConfiguration() {
        final LdpResource resource = new LdpResource("a/members", InteractionModel.DIRECT_CONTAINER);
        resource.setTriples(List.of(Triple.iri("/a/members", Ldp.MEMBERSHIP_RESOURCE, "/a"),
            Triple.iri("/a/members", Ldp.IS_MEMBER_OF_RELATION, "http://pcdm.org/models#memberOf")));
        LdpRules.configureMembership(resource);
        assertEquals("/a", resource.getMembershipResource());
        assertNull(resource.getHasMemberRelation());
        assertEquals("http://pcdm.org/models#memberOf", resource.getIsMemberOfRelation());
        assertEquals(2, resource.getTriples().size());
    }

    @Test
    void rejectsSeveralMembershipResourcesOrRelations() {
        final LdpResource twoResources = new LdpResource("a", InteractionModel.DIRECT_CONTAINER);
        twoResources.setTriples(List.of(Triple.iri("/a", Ldp.MEMBERSHIP_RESOURCE, "/b"),
            Triple.iri("/a", Ldp.MEMBERSHIP_RESOURCE, "/c")));
        assertThrows(ConstraintViolationException.class, () -> LdpRules.configureMembership(twoResources));

        final LdpResource twoRelations = new LdpResource("a", InteractionModel.DIRECT_CONTAINER);
        twoRelations.setTriples(List.of(Triple.iri("/a", Ldp.HAS_MEMBER_RELATION, HAS_MEMBER),
            Triple.iri("/a", Ldp.IS_MEMBER_OF_RELATION, "http://x/of")));
        assertThrows(ConstraintViolationException.class, () -> LdpRules.configureMembership(twoRelations));
    }

    @Test
    void rejectsLiteralMembershipValues() {
        final LdpResource resource = new LdpResource("a", InteractionModel.DIRECT_CONTAINER);
        resource.setTriples(List.of(Triple.literal("/a", Ldp.MEMBERSHIP_RESOURCE, "/b")));
        assertThrows(ConstraintViolationException.class, () -> LdpRules.configureMembership(resource));
    }

    @Test
    void indirectContainerRequiresInsertedContentRelation() {
        final LdpResource missing = new LdpResource("a", InteractionModel.INDIRECT_CONTAINER);
        assertThrows(ConstraintViolationException.class, () -> LdpRules.configureMembership(missing));

        final LdpResource valid = new LdpResource("a", InteractionModel.INDIRECT_CONTAINER);
        valid.setTriples(List.of(Triple.iri("/a", Ldp.INSERTED_CONTENT_RELATION, PROXY_FOR)));
        LdpRules.configureMembership(valid);
        assertEquals(PROXY_FOR, valid.getInsertedContentRelation());
    }

    // -- membership computation -----------------------------------------------------------------

    @Test
    void directContainerMembersAreTheContainedResources() {
        final LdpResource container = new LdpResource("a", InteractionModel.DIRECT_CONTAINER);
        LdpRules.configureMembership(container);
        final LdpResource member = new LdpResource("a/1", InteractionModel.RDF_SOURCE);
        assertEquals(List.of("/a/1"), LdpRules.memberIris(container, member));
        assertTrue(LdpRules.isMemberSubject(container));
    }

    @Test
    void indirectContainerMembersComeFromInsertedContent() {
        final LdpResource container = new LdpResource("a", InteractionModel.INDIRECT_CONTAINER);
        container.setTriples(List.of(Triple.iri("/a", Ldp.INSERTED_CONTENT_RELATION, PROXY_FOR)));
        LdpRules.configureMembership(container);
        final LdpResource proxy = new LdpResource("a/p1", InteractionModel.RDF_SOURCE);
        proxy.setTriples(List.of(Triple.iri("/a/p1", PROXY_FOR, "/objects/1"),
            Triple.literal("/a/p1", PROXY_FOR, "not an IRI"), Triple.iri("/other", PROXY_FOR, "/objects/2")));
        assertEquals(List.of("/objects/1"), LdpRules.memberIris(container, proxy));
    }

    @Test
    void indirectContainerWithMemberSubjectBehavesLikeDirect() {
        final LdpResource container = new LdpResource("a", InteractionModel.INDIRECT_CONTAINER);
        container.setTriples(List.of(Triple.iri("/a", Ldp.INSERTED_CONTENT_RELATION, Ldp.MEMBER_SUBJECT)));
        LdpRules.configureMembership(container);
        assertEquals(List.of("/a/1"), LdpRules.memberIris(container, new LdpResource("a/1", InteractionModel.RDF_SOURCE)));
    }

}
