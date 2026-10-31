package com.articulorum.platform.webac;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.articulorum.domain.TermKind;
import com.articulorum.domain.Triple;
import com.articulorum.domain.vocabulary.Acl;
import com.articulorum.domain.vocabulary.Rdf;

/**
 * One WebAC authorization ({@code acl:Authorization}) of an access control list.
 *
 * <p>IRIs are server-relative where they denote resources of this server (as everywhere in the
 * platform), absolute otherwise.</p>
 *
 * @param id            the authorization's IRI (usually a fragment of the ACL)
 * @param agents        {@code acl:agent} values
 * @param agentClasses  {@code acl:agentClass} values
 * @param agentGroups   {@code acl:agentGroup} values
 * @param modes         {@code acl:mode} values
 * @param accessTo      {@code acl:accessTo} values
 * @param defaults      {@code acl:default} values (containers whose descendants inherit)
 * @param accessToClass {@code acl:accessToClass} values
 */
public record Authorization(String id, Set<String> agents, Set<String> agentClasses, Set<String> agentGroups,
        Set<String> modes, Set<String> accessTo, Set<String> defaults, Set<String> accessToClass) {

    /**
     * Creates an authorization with immutable sets.
     *
     * @param id            the IRI
     * @param agents        agents
     * @param agentClasses  agent classes
     * @param agentGroups   agent groups
     * @param modes         modes
     * @param accessTo      resources
     * @param defaults      inheriting containers
     * @param accessToClass classes of resources
     */
    public Authorization {
        agents = Set.copyOf(agents);
        agentClasses = Set.copyOf(agentClasses);
        agentGroups = Set.copyOf(agentGroups);
        modes = Set.copyOf(modes);
        accessTo = Set.copyOf(accessTo);
        defaults = Set.copyOf(defaults);
        accessToClass = Set.copyOf(accessToClass);
    }

    /**
     * Extracts the authorizations of an access control list: every subject typed
     * {@code acl:Authorization}.
     *
     * @param triples the ACL's triples
     * @return the authorizations, in document order
     */
    public static List<Authorization> parse(final List<Triple> triples) {
        final Map<String, Map<String, Set<String>>> subjects = new LinkedHashMap<>();
        final Set<String> authorizations = new LinkedHashSet<>();
        for (final Triple triple : triples) {
            if (triple.getObjectKind() == TermKind.LITERAL) {
                continue;
            }
            if (Rdf.TYPE.equals(triple.getPredicate()) && Acl.AUTHORIZATION.equals(triple.getObject())) {
                authorizations.add(triple.getSubject());
            }
            subjects.computeIfAbsent(triple.getSubject(), key -> new LinkedHashMap<>())
                .computeIfAbsent(triple.getPredicate(), key -> new LinkedHashSet<>())
                .add(triple.getObject());
        }
        final List<Authorization> result = new ArrayList<>();
        for (final String subject : authorizations) {
            final Map<String, Set<String>> values = subjects.get(subject);
            result.add(new Authorization(subject, values(values, Acl.AGENT), values(values, Acl.AGENT_CLASS),
                values(values, Acl.AGENT_GROUP), values(values, Acl.MODE), values(values, Acl.ACCESS_TO),
                values(values, Acl.DEFAULT), values(values, Acl.ACCESS_TO_CLASS)));
        }
        return result;
    }

    private static Set<String> values(final Map<String, Set<String>> values, final String predicate) {
        return values.getOrDefault(predicate, Set.of());
    }

    /**
     * Tells whether the authorization grants a mode; {@code acl:Write} implies {@code acl:Append}.
     *
     * @param mode the mode
     * @return {@code true} when granted
     */
    public boolean grants(final String mode) {
        return modes.contains(mode) || (Acl.APPEND.equals(mode) && modes.contains(Acl.WRITE));
    }

}
