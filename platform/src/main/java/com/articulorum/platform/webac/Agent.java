package com.articulorum.platform.webac;

import java.util.Set;

/**
 * The agent making a request, as Web Access Control sees it.
 *
 * @param webId         the agent's identifier (a WebID or other IRI), {@code null} when anonymous
 * @param groups        IRIs of groups the agent belongs to by virtue of its credentials (for
 *                      example Consul roles), in addition to {@code vcard:Group} resources listing it
 * @param authenticated whether the agent presented valid credentials
 */
public record Agent(String webId, Set<String> groups, boolean authenticated) {

    /** The anonymous agent. */
    public static final Agent ANONYMOUS = new Agent(null, Set.of(), false);

    /**
     * Creates an agent.
     *
     * @param webId         the identifier
     * @param groups        group IRIs
     * @param authenticated whether credentials were presented
     */
    public Agent {
        groups = groups == null ? Set.of() : Set.copyOf(groups);
    }

    /**
     * Creates an authenticated agent without credential groups.
     *
     * @param webId the identifier
     * @return the agent
     */
    public static Agent of(final String webId) {
        return new Agent(webId, Set.of(), true);
    }

}
