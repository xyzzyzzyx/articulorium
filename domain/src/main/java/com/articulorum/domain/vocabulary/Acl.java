package com.articulorum.domain.vocabulary;

import java.util.List;

/**
 * Terms of the W3C/Solid Web Access Control vocabulary ({@code acl:}) together with the FOAF and
 * vCard terms WebAC authorizations refer to.
 *
 * @see <a href="https://solidproject.org/TR/wac">Web Access Control</a>
 */
public final class Acl {

    /** The WebAC namespace. */
    public static final String NAMESPACE = "http://www.w3.org/ns/auth/acl#";

    /** Preferred prefix of {@link #NAMESPACE}. */
    public static final String PREFIX = "acl";

    /** Class of an authorization. */
    public static final String AUTHORIZATION = NAMESPACE + "Authorization";

    /** The resource an authorization applies to. */
    public static final String ACCESS_TO = NAMESPACE + "accessTo";

    /** The container whose descendants inherit an authorization. */
    public static final String DEFAULT = NAMESPACE + "default";

    /** Applies an authorization to every resource with an {@code rdf:type}. */
    public static final String ACCESS_TO_CLASS = NAMESPACE + "accessToClass";

    /** An agent (WebID) granted access. */
    public static final String AGENT = NAMESPACE + "agent";

    /** A class of agents granted access ({@link #FOAF_AGENT} or {@link #AUTHENTICATED_AGENT}). */
    public static final String AGENT_CLASS = NAMESPACE + "agentClass";

    /** A group of agents granted access. */
    public static final String AGENT_GROUP = NAMESPACE + "agentGroup";

    /** An access mode granted. */
    public static final String MODE = NAMESPACE + "mode";

    /** Read access. */
    public static final String READ = NAMESPACE + "Read";

    /** Write access (implies {@link #APPEND}). */
    public static final String WRITE = NAMESPACE + "Write";

    /** Append access. */
    public static final String APPEND = NAMESPACE + "Append";

    /** Access to the access control list itself. */
    public static final String CONTROL = NAMESPACE + "Control";

    /** Every authenticated agent. */
    public static final String AUTHENTICATED_AGENT = NAMESPACE + "AuthenticatedAgent";

    /** Every agent, authenticated or not. */
    public static final String FOAF_AGENT = "http://xmlns.com/foaf/0.1/Agent";

    /** The vCard namespace. */
    public static final String VCARD_NAMESPACE = "http://www.w3.org/2006/vcard/ns#";

    /** Class of a group of agents. */
    public static final String VCARD_GROUP = VCARD_NAMESPACE + "Group";

    /** A member of a {@link #VCARD_GROUP}. */
    public static final String VCARD_HAS_MEMBER = VCARD_NAMESPACE + "hasMember";

    /** Link relation of an access control list ({@code Link: <...>; rel="acl"}). */
    public static final String REL_ACL = "acl";

    /** Every access mode, in display order. */
    public static final List<String> MODES = List.of(READ, WRITE, APPEND, CONTROL);

    private Acl() {
        // constants holder
    }

}
