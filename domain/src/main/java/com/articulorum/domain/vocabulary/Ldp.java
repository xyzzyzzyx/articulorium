package com.articulorum.domain.vocabulary;

/**
 * Terms of the W3C Linked Data Platform 1.0 vocabulary.
 *
 * @see <a href="https://www.w3.org/TR/ldp/">Linked Data Platform 1.0</a>
 * @see <a href="https://www.w3.org/ns/ldp">LDP vocabulary</a>
 */
public final class Ldp {

    /** The LDP namespace IRI. */
    public static final String NAMESPACE = "http://www.w3.org/ns/ldp#";

    /** Conventional prefix for {@link #NAMESPACE}. */
    public static final String PREFIX = "ldp";

    /** Class of every resource served by an LDP server. */
    public static final String RESOURCE = NAMESPACE + "Resource";

    /** Class of resources whose state is fully represented in RDF. */
    public static final String RDF_SOURCE = NAMESPACE + "RDFSource";

    /** Class of resources whose state is not represented in RDF (binaries, files). */
    public static final String NON_RDF_SOURCE = NAMESPACE + "NonRDFSource";

    /** Class of RDF sources that also contain other resources. */
    public static final String CONTAINER = NAMESPACE + "Container";

    /** Linked Data Notifications inbox of a resource ({@code ldp:inbox}, also a link relation). */
    public static final String INBOX = NAMESPACE + "inbox";

    /** Container that only maintains containment triples. */
    public static final String BASIC_CONTAINER = NAMESPACE + "BasicContainer";

    /** Container that maintains membership triples whose object is the member's IRI. */
    public static final String DIRECT_CONTAINER = NAMESPACE + "DirectContainer";

    /** Container that maintains membership triples derived from the member's content. */
    public static final String INDIRECT_CONTAINER = NAMESPACE + "IndirectContainer";

    /** Containment predicate linking a container to the resources it contains. */
    public static final String CONTAINS = NAMESPACE + "contains";

    /** Membership-resource predicate of direct and indirect containers. */
    public static final String MEMBERSHIP_RESOURCE = NAMESPACE + "membershipResource";

    /** Membership predicate whose subject is the membership resource. */
    public static final String HAS_MEMBER_RELATION = NAMESPACE + "hasMemberRelation";

    /** Membership predicate whose subject is the member. */
    public static final String IS_MEMBER_OF_RELATION = NAMESPACE + "isMemberOfRelation";

    /** Predicate naming the property of the member whose object becomes the member of an indirect container. */
    public static final String INSERTED_CONTENT_RELATION = NAMESPACE + "insertedContentRelation";

    /** Default membership predicate used when a container declares neither membership relation. */
    public static final String MEMBER = NAMESPACE + "member";

    /** Value of {@link #INSERTED_CONTENT_RELATION} meaning "use the member resource itself". */
    public static final String MEMBER_SUBJECT = NAMESPACE + "MemberSubject";

    /** Link relation advertising the server's constraints document. */
    public static final String CONSTRAINED_BY = NAMESPACE + "constrainedBy";

    /** Prefer header token: include only the container's own triples. */
    public static final String PREFER_MINIMAL_CONTAINER = NAMESPACE + "PreferMinimalContainer";

    /** Prefer header token: containment triples. */
    public static final String PREFER_CONTAINMENT = NAMESPACE + "PreferContainment";

    /** Prefer header token: membership triples. */
    public static final String PREFER_MEMBERSHIP = NAMESPACE + "PreferMembership";

    /** Legacy (pre-recommendation) spelling of {@link #PREFER_MINIMAL_CONTAINER}, still sent by some clients. */
    public static final String PREFER_EMPTY_CONTAINER = NAMESPACE + "PreferEmptyContainer";

    private Ldp() {
        // constants holder
    }

}
