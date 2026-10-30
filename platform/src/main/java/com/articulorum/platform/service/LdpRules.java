package com.articulorum.platform.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.TermKind;
import com.articulorum.domain.Triple;
import com.articulorum.domain.vocabulary.Fedora;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.domain.vocabulary.Premis;
import com.articulorum.domain.vocabulary.Rdf;
import com.articulorum.platform.ldp.exception.ConstraintViolationException;

/**
 * The pure (repository-independent) rules of the Linked Data Platform applied by
 * {@link LdpService}: interaction model inference, separation of client-managed from
 * server-managed triples, membership configuration and membership computation.
 *
 * <p>Keeping these rules free of persistence and HTTP concerns makes them easy to reason about
 * and to test exhaustively.</p>
 */
public final class LdpRules {

    /** Predicates of non-RDF source technical metadata; clients may not set them. */
    public static final Set<String> TECHNICAL_PREDICATES =
        Set.of(Premis.HAS_SIZE, Premis.HAS_MESSAGE_DIGEST, Premis.HAS_MIME_TYPE, Premis.FILENAME);

    private LdpRules() {
        // utility class
    }

    /**
     * Chooses the interaction model of a new RDF resource created without a {@code Link} header.
     *
     * <p>An explicit LDP {@code rdf:type} in the body wins. Otherwise a body that configures
     * membership creates a direct container (an indirect one when it also declares
     * {@code ldp:insertedContentRelation}); anything else creates a basic container.</p>
     *
     * @param subject  the relative IRI of the new resource
     * @param incoming the parsed body
     * @return the interaction model
     */
    public static InteractionModel inferModel(final String subject, final List<Triple> incoming) {
        final Set<String> types = new HashSet<>();
        final Set<String> predicates = new HashSet<>();
        for (final Triple triple : incoming) {
            if (subject.equals(triple.getSubject())) {
                predicates.add(triple.getPredicate());
                if (Rdf.TYPE.equals(triple.getPredicate()) && triple.getObjectKind() == TermKind.IRI) {
                    types.add(triple.getObject());
                }
            }
        }
        for (final String type : List.of(Ldp.NON_RDF_SOURCE, Ldp.INDIRECT_CONTAINER, Ldp.DIRECT_CONTAINER,
                Ldp.BASIC_CONTAINER, Ldp.CONTAINER)) {
            if (types.contains(type)) {
                return InteractionModel.fromIri(type).orElseThrow();
            }
        }
        final boolean membership = predicates.contains(Ldp.MEMBERSHIP_RESOURCE)
            || predicates.contains(Ldp.HAS_MEMBER_RELATION) || predicates.contains(Ldp.IS_MEMBER_OF_RELATION);
        if (predicates.contains(Ldp.INSERTED_CONTENT_RELATION) && membership) {
            return InteractionModel.INDIRECT_CONTAINER;
        }
        if (membership) {
            return InteractionModel.DIRECT_CONTAINER;
        }
        if (types.contains(Ldp.RDF_SOURCE)) {
            return InteractionModel.RDF_SOURCE;
        }
        return InteractionModel.BASIC_CONTAINER;
    }

    /**
     * Filters the triples of a request body down to the client-managed triples to store.
     *
     * <ul>
     *   <li>Triples equal to a current server-managed triple are dropped (clients may echo the
     *       representation they received).</li>
     *   <li>LDP {@code rdf:type}s of the resource that agree with its interaction model are
     *       dropped; any other LDP type is a forbidden model change.</li>
     *   <li>Any other {@code ldp:contains} triple, and technical metadata of a non-RDF source,
     *       is a forbidden modification of server-managed state.</li>
     * </ul>
     *
     * @param resource      the resource being written
     * @param incoming      the triples of the body
     * @param serverManaged the resource's current server-managed triples
     * @return the triples to store
     * @throws ConstraintViolationException when the body modifies server-managed state
     */
    public static List<Triple> acceptClientTriples(final LdpResource resource, final List<Triple> incoming,
            final List<Triple> serverManaged) {
        final Set<Triple> current = new HashSet<>(serverManaged);
        final Set<String> modelTypes = new HashSet<>(resource.getInteractionModel().getTypeIris());
        final String subject = resource.getRelativeIri();
        final List<Triple> accepted = new ArrayList<>();
        for (final Triple triple : incoming) {
            if (current.contains(triple)) {
                continue;
            }
            if (isFedoraMetadata(subject, triple)) {
                continue;
            }
            if (Ldp.CONTAINS.equals(triple.getPredicate())) {
                throw new ConstraintViolationException("ldp:contains is server-managed and cannot be modified: " + triple);
            }
            if (subject.equals(triple.getSubject()) && Rdf.TYPE.equals(triple.getPredicate())
                    && triple.getObjectKind() == TermKind.IRI && triple.getObject().startsWith(Ldp.NAMESPACE)) {
                if (modelTypes.contains(triple.getObject())) {
                    continue;
                }
                throw new ConstraintViolationException("The interaction model of " + subject + " is "
                    + resource.getInteractionModel().getIri() + " and cannot be changed to " + triple.getObject());
            }
            if (resource.isNonRdfSource() && subject.equals(triple.getSubject())
                    && TECHNICAL_PREDICATES.contains(triple.getPredicate())) {
                throw new ConstraintViolationException(triple.getPredicate() + " is server-managed and cannot be modified");
            }
            accepted.add(triple);
        }
        return accepted;
    }

    /**
     * Tells whether a triple is Fedora repository metadata about a resource: a {@code fedora:}
     * predicate, or an {@code rdf:type} with a {@code fedora:} class.
     *
     * @param subject the relative IRI of the resource
     * @param triple  the triple
     * @return {@code true} for server-managed Fedora metadata
     */
    static boolean isFedoraMetadata(final String subject, final Triple triple) {
        if (!subject.equals(triple.getSubject())) {
            return false;
        }
        if (Fedora.isFedoraTerm(triple.getPredicate())) {
            return true;
        }
        return Rdf.TYPE.equals(triple.getPredicate()) && triple.getObjectKind() == TermKind.IRI
            && Fedora.isFedoraTerm(triple.getObject());
    }

    /**
     * Validates the membership triples of a direct or indirect container and copies them into the
     * resource's membership columns (LDP 5.4.1.3, 5.4.1.4, 5.5.1.2).
     *
     * <p>{@code ldp:membershipResource} defaults to the container itself and the membership
     * predicate defaults to {@code ldp:hasMemberRelation ldp:member}; the defaults are added to
     * the stored triples so the representation states them explicitly.</p>
     *
     * @param resource the resource whose triples were just set
     * @throws ConstraintViolationException when the configuration is invalid
     */
    public static void configureMembership(final LdpResource resource) {
        resource.setMembershipResource(null);
        resource.setHasMemberRelation(null);
        resource.setIsMemberOfRelation(null);
        resource.setInsertedContentRelation(null);
        if (!resource.getInteractionModel().isMembershipContainer()) {
            return;
        }
        final String subject = resource.getRelativeIri();
        final List<Triple> triples = new ArrayList<>(resource.getTriples());

        final List<String> membershipResources = iriValues(triples, subject, Ldp.MEMBERSHIP_RESOURCE);
        if (membershipResources.size() > 1) {
            throw new ConstraintViolationException("A container may have only one ldp:membershipResource");
        }
        if (membershipResources.isEmpty()) {
            triples.add(Triple.iri(subject, Ldp.MEMBERSHIP_RESOURCE, subject));
            membershipResources.add(subject);
        }

        final List<String> hasMember = iriValues(triples, subject, Ldp.HAS_MEMBER_RELATION);
        final List<String> isMemberOf = iriValues(triples, subject, Ldp.IS_MEMBER_OF_RELATION);
        if (hasMember.size() + isMemberOf.size() > 1) {
            throw new ConstraintViolationException(
                "A container must have exactly one ldp:hasMemberRelation or ldp:isMemberOfRelation");
        }
        if (hasMember.isEmpty() && isMemberOf.isEmpty()) {
            triples.add(Triple.iri(subject, Ldp.HAS_MEMBER_RELATION, Ldp.MEMBER));
            hasMember.add(Ldp.MEMBER);
        }

        String insertedContentRelation = null;
        if (resource.getInteractionModel() == InteractionModel.INDIRECT_CONTAINER) {
            final List<String> inserted = iriValues(triples, subject, Ldp.INSERTED_CONTENT_RELATION);
            if (inserted.size() != 1) {
                throw new ConstraintViolationException(
                    "An indirect container must have exactly one ldp:insertedContentRelation");
            }
            insertedContentRelation = inserted.get(0);
        }

        resource.setTriples(triples);
        resource.setMembershipResource(membershipResources.get(0));
        resource.setHasMemberRelation(hasMember.isEmpty() ? null : hasMember.get(0));
        resource.setIsMemberOfRelation(isMemberOf.isEmpty() ? null : isMemberOf.get(0));
        resource.setInsertedContentRelation(insertedContentRelation);
    }

    private static List<String> iriValues(final List<Triple> triples, final String subject, final String predicate) {
        final List<String> values = new ArrayList<>();
        for (final Triple triple : triples) {
            if (triple.matches(subject, predicate)) {
                if (triple.getObjectKind() != TermKind.IRI) {
                    throw new ConstraintViolationException(predicate + " must have an IRI value");
                }
                values.add(triple.getObject());
            }
        }
        return values;
    }

    /**
     * Computes the member IRIs contributed by one contained resource.
     *
     * <p>For a direct container (or an indirect container using {@code ldp:MemberSubject}) the
     * member is the contained resource itself. For an indirect container the members are the IRI
     * objects of the contained resource's {@code ldp:insertedContentRelation} property
     * (LDP 5.5.1.2).</p>
     *
     * @param container the membership container
     * @param member    a resource it contains
     * @return the member IRIs
     */
    public static List<String> memberIris(final LdpResource container, final LdpResource member) {
        if (isMemberSubject(container)) {
            return List.of(member.getRelativeIri());
        }
        final List<String> iris = new ArrayList<>();
        for (final Triple triple : member.getTriples()) {
            if (triple.matches(member.getRelativeIri(), container.getInsertedContentRelation())
                    && triple.getObjectKind() == TermKind.IRI) {
                iris.add(triple.getObject());
            }
        }
        return iris;
    }

    public static boolean isMemberSubject(final LdpResource container) {
        return container.getInteractionModel() == InteractionModel.DIRECT_CONTAINER
            || (container.getInteractionModel() == InteractionModel.INDIRECT_CONTAINER
                && Ldp.MEMBER_SUBJECT.equals(container.getInsertedContentRelation()));
    }

}
