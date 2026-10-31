package com.articulorum.platform.webac;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.articulorum.domain.LdpResource;
import com.articulorum.domain.TermKind;
import com.articulorum.domain.Triple;
import com.articulorum.domain.rdf.Iris;
import com.articulorum.domain.rdf.NTriplesSerializer;
import com.articulorum.domain.rdf.ResourcePaths;
import com.articulorum.domain.vocabulary.Acl;
import com.articulorum.domain.vocabulary.Fedora;
import com.articulorum.domain.vocabulary.Rdf;
import com.articulorum.platform.ldp.exception.BadRequestException;
import com.articulorum.platform.ldp.exception.ConstraintViolationException;
import com.articulorum.platform.ldp.exception.ResourceNotFoundException;
import com.articulorum.platform.rdf.TripleMapper;
import com.articulorum.platform.repository.LdpResourceRepository;
import com.articulorum.platform.service.LdpService;
import com.articulorum.platform.service.ResourceDeletionListener;

import org.apache.jena.query.QueryException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.apache.jena.update.UpdateAction;
import org.apache.jena.update.UpdateFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Web Access Control: stores access control lists and decides which access modes an agent has on
 * a resource.
 *
 * <h2>Effective ACL</h2>
 * <p>The ACL that governs a resource is its own ({@code <resource>/fcr:acl}) when it has one;
 * otherwise the ACL of the nearest ancestor that has one, of which only the authorizations naming
 * that ancestor with {@code acl:default} apply (inheritance); otherwise the configured root ACL
 * ({@link WebAcProperties#getRootAcl()}). The description of a binary shares the binary's ACL.</p>
 *
 * <h2>Matching</h2>
 * <p>An applicable authorization grants its {@code acl:mode}s to an agent named by
 * {@code acl:agent}, to everyone ({@code acl:agentClass foaf:Agent}), to every authenticated agent
 * ({@code acl:agentClass acl:AuthenticatedAgent}), and to members of an {@code acl:agentGroup}:
 * a group the agent's credentials put it in (for example a Consul role), or a {@code vcard:Group}
 * resource in this repository that lists the agent with {@code vcard:hasMember}. Authorizations
 * with {@code acl:accessToClass} apply to every resource with that {@code rdf:type}.
 * {@code acl:Write} implies {@code acl:Append}.</p>
 */
@Service
public class AccessControlService implements ResourceDeletionListener {

    /** Base used to store ACL triples, so that stored ACLs never depend on a host name. */
    static final String STORAGE_BASE = LdpService.SNAPSHOT_BASE;

    /** Base used to parse the root ACL document; {@code <>} resolves to the root container. */
    static final String ROOT_ACL_BASE = "http://articulorum.invalid";

    /**
     * The access control list governing a resource.
     *
     * @param resourcePath   the path of the resource access is decided for
     * @param aclPath        the path of the resource whose ACL applies, {@code null} for the
     *                       configured root ACL
     * @param inherited      whether the ACL is inherited from an ancestor (only {@code acl:default}
     *                       authorizations apply)
     * @param authorizations the ACL's authorizations
     */
    public record EffectiveAcl(String resourcePath, String aclPath, boolean inherited,
            List<Authorization> authorizations) {
    }

    private final AclRepository acls;

    private final LdpResourceRepository resources;

    private final List<Triple> rootAcl;

    /**
     * Creates the service.
     *
     * @param acls       stored access control lists
     * @param resources  the resource repository
     * @param properties WebAC settings
     */
    public AccessControlService(final AclRepository acls, final LdpResourceRepository resources,
            final WebAcProperties properties) {
        this.acls = acls;
        this.resources = resources;
        this.rootAcl = readRootAcl(properties);
    }

    // ------------------------------------------------------------------------------------------
    // Decisions
    // ------------------------------------------------------------------------------------------

    /**
     * Returns the path access is decided for: the path itself, or the binary when the path
     * addresses a binary's description.
     *
     * @param path the request path
     * @return the normalised target path
     */
    @Transactional(readOnly = true)
    public String targetPath(final String path) {
        final String normalized = ResourcePaths.normalize(path);
        if (LdpService.DESCRIPTION_SEGMENT.equals(ResourcePaths.nameOf(normalized))) {
            final Optional<String> parent = ResourcePaths.parentOf(normalized);
            if (parent.isPresent() && !resources.existsByPath(normalized)
                    && resources.findByPath(parent.get()).map(LdpResource::isNonRdfSource).orElse(false)) {
                return parent.get();
            }
        }
        return normalized;
    }

    /**
     * Determines the ACL governing a path.
     *
     * @param path the request path (the resource need not exist)
     * @return the effective ACL
     */
    @Transactional(readOnly = true)
    public EffectiveAcl effective(final String path) {
        final String target = targetPath(path);
        final List<String> candidates = selfAndAncestors(target);
        final Map<String, AclEntity> found = acls.findByPathIn(candidates).stream()
            .collect(Collectors.toMap(AclEntity::getPath, Function.identity()));
        for (final String candidate : candidates) {
            final AclEntity acl = found.get(candidate);
            if (acl != null) {
                return new EffectiveAcl(target, candidate, !candidate.equals(target),
                    Authorization.parse(read(acl.getContent())));
            }
        }
        return new EffectiveAcl(target, null, !target.isEmpty(), Authorization.parse(rootAcl));
    }

    /**
     * Computes the access modes an agent has on a resource.
     *
     * @param agent the agent
     * @param path  the request path
     * @return the granted modes ({@link Acl#READ}, {@link Acl#WRITE}, {@link Acl#APPEND},
     *         {@link Acl#CONTROL})
     */
    @Transactional(readOnly = true)
    public Set<String> modes(final Agent agent, final String path) {
        return modes(agent, effective(path));
    }

    /**
     * Computes the access modes an agent has under an effective ACL.
     *
     * @param agent     the agent
     * @param effective the effective ACL of the resource
     * @return the granted modes
     */
    @Transactional(readOnly = true)
    public Set<String> modes(final Agent agent, final EffectiveAcl effective) {
        final String targetIri = ResourcePaths.toRelativeIri(effective.resourcePath());
        final String aclIri = ResourcePaths.toRelativeIri(effective.aclPath() == null ? "" : effective.aclPath());
        Set<String> types = null;
        final Set<String> granted = new LinkedHashSet<>();
        for (final Authorization authorization : effective.authorizations()) {
            boolean applies = effective.inherited()
                ? authorization.defaults().contains(aclIri)
                : authorization.accessTo().contains(targetIri);
            if (!applies && !authorization.accessToClass().isEmpty()) {
                if (types == null) {
                    types = typesOf(effective.resourcePath());
                }
                applies = authorization.accessToClass().stream().anyMatch(types::contains);
            }
            if (applies && matchesAgent(authorization, agent)) {
                for (final String mode : Acl.MODES) {
                    if (authorization.grants(mode)) {
                        granted.add(mode);
                    }
                }
            }
        }
        return granted;
    }

    /**
     * Tells whether an agent has a mode on a resource.
     *
     * @param agent the agent
     * @param path  the request path
     * @param mode  the required mode
     * @return {@code true} when granted
     */
    @Transactional(readOnly = true)
    public boolean isAllowed(final Agent agent, final String path, final String mode) {
        return modes(agent, path).contains(mode);
    }

    /**
     * Tells whether an authorization names an agent.
     *
     * @param authorization the authorization
     * @param agent         the agent
     * @return {@code true} when the agent is covered
     */
    boolean matchesAgent(final Authorization authorization, final Agent agent) {
        if (authorization.agentClasses().contains(Acl.FOAF_AGENT)) {
            return true;
        }
        if (agent.authenticated() && authorization.agentClasses().contains(Acl.AUTHENTICATED_AGENT)) {
            return true;
        }
        if (agent.webId() == null) {
            return false;
        }
        if (authorization.agents().contains(agent.webId())) {
            return true;
        }
        for (final String group : authorization.agentGroups()) {
            if (agent.groups().contains(group) || isLocalGroupMember(group, agent.webId())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Tells whether a {@code vcard:Group} resource of this repository lists an agent.
     *
     * @param group the group's IRI (server-relative for local groups)
     * @param webId the agent
     * @return {@code true} when the group resource lists the agent with {@code vcard:hasMember}
     */
    private boolean isLocalGroupMember(final String group, final String webId) {
        return ResourcePaths.fromRelativeIri(group)
            .flatMap(resources::findByPath)
            .map(resource -> resource.getTriples().stream().anyMatch(triple -> group.equals(triple.getSubject())
                && Acl.VCARD_HAS_MEMBER.equals(triple.getPredicate()) && webId.equals(triple.getObject())))
            .orElse(false);
    }

    private Set<String> typesOf(final String path) {
        final Optional<LdpResource> resource = resources.findByPath(path);
        if (resource.isEmpty()) {
            return Set.of();
        }
        final Set<String> types = new HashSet<>(resource.get().getInteractionModel().getTypeIris());
        types.add(Fedora.RESOURCE);
        final String subject = resource.get().getRelativeIri();
        for (final Triple triple : resource.get().getTriples()) {
            if (subject.equals(triple.getSubject()) && Rdf.TYPE.equals(triple.getPredicate())
                    && triple.getObjectKind() == TermKind.IRI) {
                types.add(triple.getObject());
            }
        }
        return types;
    }

    // ------------------------------------------------------------------------------------------
    // ACL documents
    // ------------------------------------------------------------------------------------------

    /**
     * Returns the triples of a resource's own ACL.
     *
     * @param path the resource path
     * @return the triples (server-relative IRIs), or empty when the resource has no ACL
     * @throws ResourceNotFoundException when the resource does not exist
     */
    @Transactional(readOnly = true)
    public Optional<List<Triple>> find(final String path) {
        final String target = existingTarget(path);
        return acls.findByPath(target).map(acl -> read(acl.getContent()));
    }

    /**
     * Creates or replaces a resource's ACL.
     *
     * @param path    the resource path
     * @param triples the new ACL (server-relative IRIs)
     * @param agent   who changes it
     * @return {@code true} when the ACL was created, {@code false} when it was replaced
     * @throws ResourceNotFoundException when the resource does not exist
     */
    @Transactional
    public boolean put(final String path, final List<Triple> triples, final String agent) {
        final String target = existingTarget(path);
        validate(triples);
        final String content = NTriplesSerializer.serialize(triples, STORAGE_BASE);
        if (content.length() > AclEntity.MAX_LENGTH) {
            throw new ConstraintViolationException("The access control list is too large");
        }
        final Optional<AclEntity> existing = acls.findByPath(target);
        if (existing.isPresent()) {
            final AclEntity acl = existing.get();
            acl.setContent(content);
            acl.setModified(java.time.Instant.now());
            acl.setModifiedBy(agent);
            acls.save(acl);
            return false;
        }
        acls.save(new AclEntity(target, content, agent));
        return true;
    }

    /**
     * Applies a SPARQL Update to a resource's ACL (an empty ACL when it has none).
     *
     * @param path   the resource path
     * @param update the SPARQL Update
     * @param base   the server base, used to resolve relative IRIs of the update
     * @param agent  who changes it
     * @throws BadRequestException when the update is invalid
     */
    @Transactional
    public void patch(final String path, final String update, final String base, final String agent) {
        final String target = existingTarget(path);
        final List<Triple> current = acls.findByPath(target).map(acl -> read(acl.getContent())).orElse(List.of());
        final Model model = TripleMapper.toModel(current, base);
        try {
            UpdateAction.execute(UpdateFactory.create(update, aclIri(base, target)), model);
        } catch (QueryException e) {
            throw new BadRequestException("Invalid SPARQL Update: " + e.getMessage(), e);
        }
        put(target, TripleMapper.toTriples(model, base), agent);
    }

    /**
     * Deletes a resource's ACL; the resource then inherits again.
     *
     * @param path the resource path
     * @throws ResourceNotFoundException when the resource or its ACL does not exist
     */
    @Transactional
    public void delete(final String path) {
        final String target = existingTarget(path);
        final AclEntity acl = acls.findByPath(target)
            .orElseThrow(() -> new ResourceNotFoundException(target + "/fcr:acl"));
        acls.delete(acl);
    }

    /**
     * Deletes the ACLs of a resource and everything below it (when the resources are deleted).
     *
     * @param path the resource path
     */
    @Override
    @Transactional
    public void subtreeDeleted(final String path) {
        final String normalized = ResourcePaths.normalize(path);
        acls.deleteSubtree(normalized, normalized + "/");
    }

    /**
     * Parses an ACL document.
     *
     * @param content the RDF bytes
     * @param lang    their syntax
     * @param base    the server base
     * @param path    the resource path (relative IRIs resolve against its ACL's IRI)
     * @return the triples, with server-relative IRIs
     * @throws BadRequestException when the document cannot be parsed
     */
    public static List<Triple> parse(final byte[] content, final Lang lang, final String base, final String path) {
        final Model model = ModelFactory.createDefaultModel();
        try {
            RDFDataMgr.read(model, new ByteArrayInputStream(content), aclIri(base, ResourcePaths.normalize(path)), lang);
        } catch (RiotException e) {
            throw new BadRequestException("Invalid " + lang.getName() + ": " + e.getMessage(), e);
        }
        return TripleMapper.toTriples(model, base);
    }

    /**
     * Returns the IRI of a resource's ACL.
     *
     * @param base the server base
     * @param path the resource path
     * @return {@code <resource>/fcr:acl}
     */
    public static String aclIri(final String base, final String path) {
        final String iri = Iris.resolve(base, ResourcePaths.toRelativeIri(path));
        return (iri.endsWith("/") ? iri : iri + "/") + "fcr:acl";
    }

    private String existingTarget(final String path) {
        final String target = targetPath(path);
        if (!resources.existsByPath(target)) {
            throw new ResourceNotFoundException(target);
        }
        return target;
    }

    /**
     * Checks that every authorization of an ACL is usable: it names at least one mode and one
     * agent, agent class or group, and only known modes.
     *
     * @param triples the ACL
     * @throws ConstraintViolationException when an authorization is incomplete
     */
    static void validate(final List<Triple> triples) {
        for (final Authorization authorization : Authorization.parse(triples)) {
            if (authorization.modes().isEmpty()) {
                throw new ConstraintViolationException("Authorization " + authorization.id() + " grants no acl:mode");
            }
            if (!Acl.MODES.containsAll(authorization.modes())) {
                throw new ConstraintViolationException("Authorization " + authorization.id()
                    + " uses an unknown mode; use acl:Read, acl:Write, acl:Append or acl:Control");
            }
            if (authorization.agents().isEmpty() && authorization.agentClasses().isEmpty()
                    && authorization.agentGroups().isEmpty()) {
                throw new ConstraintViolationException("Authorization " + authorization.id()
                    + " names no acl:agent, acl:agentClass or acl:agentGroup");
            }
        }
    }

    private static List<Triple> read(final String nTriples) {
        final Model model = ModelFactory.createDefaultModel();
        RDFDataMgr.read(model, new ByteArrayInputStream(nTriples.getBytes(StandardCharsets.UTF_8)),
            STORAGE_BASE + "/", Lang.NTRIPLES);
        return TripleMapper.toTriples(model, STORAGE_BASE);
    }

    private static List<Triple> readRootAcl(final WebAcProperties properties) {
        final Model model = ModelFactory.createDefaultModel();
        try (InputStream in = properties.getRootAcl().getInputStream()) {
            RDFDataMgr.read(model, in, ROOT_ACL_BASE + "/", Lang.TURTLE);
        } catch (IOException e) {
            throw new UncheckedIOException("Unable to read the root ACL " + properties.getRootAcl(), e);
        }
        final List<Triple> triples = TripleMapper.toTriples(model, ROOT_ACL_BASE);
        validate(triples);
        return List.copyOf(triples);
    }

    /**
     * Lists a path and all its ancestors including the root, deepest first.
     *
     * @param path the path
     * @return the paths
     */
    static List<String> selfAndAncestors(final String path) {
        final List<String> paths = new ArrayList<>();
        Optional<String> current = Optional.of(ResourcePaths.normalize(path));
        while (current.isPresent()) {
            paths.add(current.get());
            current = current.get().isEmpty() ? Optional.empty() : ResourcePaths.parentOf(current.get());
        }
        return paths;
    }

    /**
     * Formats modes for the {@code WAC-Allow} header ({@code read write append control}).
     *
     * @param modes the modes
     * @return the space separated lower-case names
     */
    public static String wacAllowTokens(final Collection<String> modes) {
        return Acl.MODES.stream()
            .filter(modes::contains)
            .map(mode -> mode.substring(Acl.NAMESPACE.length()).toLowerCase(java.util.Locale.ROOT))
            .collect(Collectors.joining(" "));
    }

}
