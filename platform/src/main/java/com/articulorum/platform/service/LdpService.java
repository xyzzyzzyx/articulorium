package com.articulorum.platform.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.articulorum.domain.BinaryContent;
import com.articulorum.domain.InteractionModel;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.Triple;
import com.articulorum.domain.rdf.Iris;
import com.articulorum.domain.rdf.NTriplesSerializer;
import com.articulorum.domain.rdf.ResourcePaths;
import com.articulorum.domain.vocabulary.Fedora;
import com.articulorum.domain.vocabulary.Ldp;
import com.articulorum.platform.ldp.EntityTags;
import com.articulorum.platform.ldp.LdpHeaders;
import com.articulorum.platform.ldp.Preference;
import com.articulorum.platform.ldp.Slugs;
import com.articulorum.platform.ldp.exception.BadRequestException;
import com.articulorum.platform.ldp.exception.ConstraintViolationException;
import com.articulorum.platform.ldp.exception.LdpException;
import com.articulorum.platform.ldp.exception.MethodNotAllowedException;
import com.articulorum.platform.ldp.exception.PreconditionFailedException;
import com.articulorum.platform.ldp.exception.ResourceNotFoundException;
import com.articulorum.platform.ldp.exception.UnsupportedMediaTypeException;
import com.articulorum.platform.namespace.NamespaceRegistry;
import com.articulorum.platform.rdf.RdfMediaTypes;
import com.articulorum.platform.rdf.TripleMapper;
import com.articulorum.platform.repository.LdpResourceRepository;
import com.articulorum.platform.storage.BinaryStore;
import com.articulorum.platform.storage.StorageKeys;
import com.articulorum.platform.storage.StoredVersion;

import org.apache.jena.query.QueryException;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.apache.jena.update.UpdateAction;
import org.apache.jena.update.UpdateFactory;
import org.apache.jena.update.UpdateRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import lombok.extern.slf4j.Slf4j;

/**
 * Implements the W3C Linked Data Platform 1.0 semantics on top of the resource repository and
 * the OCFL storage layer.
 *
 * <p>The service is protocol-agnostic: the web layer turns HTTP requests into calls with already
 * decoded inputs (path, requested interaction model, payload, preconditions, base IRI) and turns
 * the results or {@link LdpException}s back into HTTP responses.</p>
 *
 * <h2>Responsibilities</h2>
 * <ul>
 *   <li>Creating resources in containers (POST) with server- or slug-chosen names, and at a
 *       client-chosen path (PUT to a missing resource).</li>
 *   <li>Choosing and enforcing interaction models; rejecting changes of the model.</li>
 *   <li>Separating client-managed from server-managed triples and rejecting attempts to modify
 *       the latter (LDP 4.2.4.3, 5.2.4.1).</li>
 *   <li>Maintaining direct/indirect container membership configuration.</li>
 *   <li>Replacing (PUT) and patching (SPARQL Update) RDF state; replacing binaries, each
 *       replacement becoming a new version of the resource's OCFL object.</li>
 *   <li>Recursive deletion of containers, including their stored content and history.</li>
 *   <li>Recording the agent that creates and modifies each resource.</li>
 *   <li>Evaluating {@code If-Match}/{@code If-None-Match} preconditions.</li>
 *   <li>Updating the modification time (and therefore the entity tag) of every resource whose
 *       representation a change affects, and publishing one event per affected resource.</li>
 * </ul>
 */
@Slf4j
@Service
public class LdpService {

    /** Last path segment addressing the RDF description of a non-RDF source. */
    public static final String DESCRIPTION_SEGMENT = "description";

    /** Names that may not be used directly below the root container. */
    public static final Set<String> RESERVED_ROOT_NAMES = Set.of(".well-known");

    /**
     * Base used to serialise the description snapshots kept in the storage layer, so the
     * snapshots never depend on the host name a client happened to use.
     */
    public static final String SNAPSHOT_BASE = "info:articulorum";

    private final LdpResourceRepository repository;

    private final BinaryStore binaryStore;

    private final ServerManagedTriples serverManagedTriples;

    private final ResourceEventPublisher eventPublisher;

    private final NamespaceRegistry namespaces;

    private final CurrentAgent agent;

    /**
     * Creates the service.
     *
     * @param repository           the resource repository
     * @param binaryStore          the OCFL storage layer
     * @param serverManagedTriples computes server-managed triples
     * @param eventPublisher       publishes change events
     * @param namespaces           namespace prefixes for rendered models
     * @param agent                determines the agent making changes
     */
    public LdpService(final LdpResourceRepository repository, final BinaryStore binaryStore,
            final ServerManagedTriples serverManagedTriples, final ResourceEventPublisher eventPublisher,
            final NamespaceRegistry namespaces, final CurrentAgent agent) {
        this.repository = repository;
        this.binaryStore = binaryStore;
        this.serverManagedTriples = serverManagedTriples;
        this.eventPublisher = eventPublisher;
        this.namespaces = namespaces;
        this.agent = agent;
    }

    // ------------------------------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------------------------------

    /**
     * Finds the target addressed by a path.
     *
     * @param path the request path
     * @return the target, or empty when nothing exists at the path
     */
    @Transactional(readOnly = true)
    public Optional<ResolvedTarget> find(final String path) {
        final String normalized = ResourcePaths.normalize(path);
        final Optional<LdpResource> resource = repository.findByPath(normalized);
        if (resource.isPresent()) {
            return Optional.of(new ResolvedTarget(resource.get(), false));
        }
        if (DESCRIPTION_SEGMENT.equals(ResourcePaths.nameOf(normalized))) {
            final Optional<String> described = ResourcePaths.parentOf(normalized);
            if (described.isPresent()) {
                return repository.findByPath(described.get())
                    .filter(LdpResource::isNonRdfSource)
                    .map(binary -> new ResolvedTarget(binary, true));
            }
        }
        return Optional.empty();
    }

    /**
     * Resolves the target addressed by a path.
     *
     * @param path the request path
     * @return the target
     * @throws ResourceNotFoundException when nothing exists at the path
     */
    @Transactional(readOnly = true)
    public ResolvedTarget resolve(final String path) {
        return find(path).orElseThrow(() -> new ResourceNotFoundException(ResourcePaths.normalize(path)));
    }

    /**
     * Renders the complete RDF representation of an RDF source, container or description.
     *
     * @param target     the target
     * @param base       the server base of the request
     * @param preference which optional server-managed triples to include
     * @return the model, with namespace prefixes declared
     */
    @Transactional(readOnly = true)
    public Model render(final ResolvedTarget target, final String base, final Preference preference) {
        final Model model = TripleMapper.toModel(representation(target.resource(), preference), base);
        namespaces.applyTo(model);
        return model;
    }

    /**
     * Opens the bytes of a non-RDF source.
     *
     * @param resource the non-RDF source
     * @return the stream; the caller closes it
     * @throws IOException when the binary store fails
     */
    public InputStream openBinary(final LdpResource resource) throws IOException {
        if (resource.getBinary() == null) {
            throw new ResourceNotFoundException(resource.getPath());
        }
        return binaryStore.open(resource.getBinary().getStorageKey(), resource.getBinary().getStorageVersion(),
            StorageKeys.CONTENT);
    }

    // ------------------------------------------------------------------------------------------
    // Creating
    // ------------------------------------------------------------------------------------------

    /**
     * Creates a resource in a container (HTTP POST, LDP 5.2.3).
     *
     * @param containerPath the container path
     * @param slug          the client's suggested name, may be {@code null}
     * @param requested     the interaction model requested with a {@code Link} header, may be
     *                      {@code null}
     * @param payload       the request body
     * @param base          the server base of the request
     * @return the created resource
     */
    @Transactional
    public LdpResource create(final String containerPath, final String slug, final InteractionModel requested,
            final Payload payload, final String base) {
        final ResolvedTarget target = resolve(containerPath);
        if (target.description() || !target.resource().isContainer()) {
            throw new MethodNotAllowedException("Only containers accept POST", target.allowedMethods());
        }
        final LdpResource container = target.resource();
        return createAt(container, mintPath(container.getPath(), slug), requested, payload, base);
    }

    private LdpResource createAt(final LdpResource container, final String path, final InteractionModel requested,
            final Payload payload, final String base) {
        final ChangeSet changes = new ChangeSet();
        final String who = agent.name();
        final LdpResource resource;
        if (payload instanceof Payload.Binary binary) {
            if (requested != null && requested != InteractionModel.NON_RDF_SOURCE) {
                throw rdfRequired(requested);
            }
            resource = new LdpResource(path, InteractionModel.NON_RDF_SOURCE).createdBy(who);
            storeBinary(resource, binary, "Create " + resource.getRelativeIri());
        } else {
            final String relativeIri = ResourcePaths.toRelativeIri(path);
            final List<Triple> incoming = parse(payload, Iris.resolve(base, relativeIri), base);
            final InteractionModel model = requested != null ? requested : LdpRules.inferModel(relativeIri, incoming);
            if (model == InteractionModel.NON_RDF_SOURCE) {
                throw new ConstraintViolationException(
                    "A non-RDF source requires a binary body; send Link: <" + Ldp.NON_RDF_SOURCE + ">; rel=\"type\"");
            }
            resource = new LdpResource(path, model).createdBy(who);
            resource.setTriples(LdpRules.acceptClientTriples(resource, incoming, List.of()));
            LdpRules.configureMembership(resource);
        }
        repository.save(resource);
        changes.created(resource);
        container.touch(who);
        repository.save(container);
        changes.updated(container);
        touchMembershipResource(container.getMembershipResource(), container, changes);
        complete(changes, base);
        log.info("Created {} {}", resource.getInteractionModel(), resource.getRelativeIri());
        return resource;
    }

    /**
     * Chooses a free path for a new child of a container.
     *
     * <p>The sanitised slug is used when it is free; when it is taken a short random suffix is
     * appended (LDP 5.2.3.10 lets the server choose another name). Without a usable slug a UUID
     * is minted.</p>
     *
     * @param containerPath the container path
     * @param slug          the client's suggestion, may be {@code null}
     * @return a free child path
     */
    String mintPath(final String containerPath, final String slug) {
        final Optional<String> name = Slugs.sanitize(slug)
            .filter(candidate -> !(containerPath.isEmpty() && RESERVED_ROOT_NAMES.contains(candidate)));
        if (name.isPresent()) {
            final String candidate = ResourcePaths.child(containerPath, name.get());
            if (!repository.existsByPath(candidate)) {
                return candidate;
            }
        }
        while (true) {
            final String random = UUID.randomUUID().toString();
            final String generated = name.map(n -> n + "-" + random.substring(0, 8)).orElse(random);
            final String candidate = ResourcePaths.child(containerPath, generated);
            if (!repository.existsByPath(candidate)) {
                return candidate;
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Replacing
    // ------------------------------------------------------------------------------------------

    /**
     * Replaces a resource, or creates it at the given path (HTTP PUT, LDP 4.2.4 and 5.2.4).
     *
     * @param path        the target path
     * @param requested   the interaction model requested with a {@code Link} header, may be
     *                    {@code null}
     * @param payload     the request body
     * @param ifMatch     the {@code If-Match} header, may be {@code null}
     * @param ifNoneMatch the {@code If-None-Match} header, may be {@code null}
     * @param base        the server base of the request
     * @return the outcome
     */
    @Transactional
    public PutResult put(final String path, final InteractionModel requested, final Payload payload,
            final String ifMatch, final String ifNoneMatch, final String base) {
        final String normalized = ResourcePaths.normalize(path);
        rejectExtensionSegments(normalized);
        final Optional<ResolvedTarget> existing = find(normalized);
        if (existing.isPresent()) {
            return new PutResult(replace(existing.get(), requested, payload, ifMatch, ifNoneMatch, base), false);
        }
        if (ifMatch != null) {
            throw new PreconditionFailedException("If-Match given but /" + normalized + " does not exist");
        }
        final String containerPath = ResourcePaths.parentOf(normalized)
            .orElseThrow(() -> new ConstraintViolationException("The root container cannot be created"));
        final LdpResource container = repository.findByPath(containerPath)
            .orElseThrow(() -> new ConstraintViolationException("Parent container /" + containerPath + " does not exist"));
        if (!container.isContainer()) {
            throw new ConstraintViolationException("Parent /" + containerPath + " is not a container");
        }
        if (containerPath.isEmpty() && RESERVED_ROOT_NAMES.contains(ResourcePaths.nameOf(normalized))) {
            throw new ConstraintViolationException("The name " + ResourcePaths.nameOf(normalized) + " is reserved");
        }
        return new PutResult(createAt(container, normalized, requested, payload, base), true);
    }

    private LdpResource replace(final ResolvedTarget target, final InteractionModel requested, final Payload payload,
            final String ifMatch, final String ifNoneMatch, final String base) {
        final LdpResource resource = target.resource();
        checkPreconditions(resource, ifMatch, ifNoneMatch);
        final InteractionModel expected = target.description() ? null : resource.getInteractionModel();
        if (requested != null && expected != null && requested != expected) {
            throw new ConstraintViolationException("The interaction model of " + resource.getRelativeIri()
                + " is " + expected.getIri() + " and cannot be changed to " + requested.getIri());
        }
        final ChangeSet changes = new ChangeSet();
        final String previousMembershipResource = resource.getMembershipResource();
        if (resource.isNonRdfSource() && !target.description()) {
            if (!(payload instanceof Payload.Binary binary)) {
                throw new LdpException(HttpStatus.BAD_REQUEST, "Replacing a non-RDF source requires a body");
            }
            storeBinary(resource, binary, "Replace " + resource.getRelativeIri());
        } else {
            if (payload instanceof Payload.Binary) {
                throw rdfRequired(resource.getInteractionModel());
            }
            final List<Triple> current = serverManagedTriples.compute(resource, Preference.DEFAULT);
            final List<Triple> incoming = parse(payload, Iris.resolve(base, resource.getRelativeIri()), base);
            resource.setTriples(LdpRules.acceptClientTriples(resource, incoming, current));
            LdpRules.configureMembership(resource);
        }
        resource.touch(agent.name());
        repository.save(resource);
        changes.updated(resource);
        touchMembershipChanges(resource, previousMembershipResource, changes);
        touchParentMembership(resource, changes);
        complete(changes, base);
        return resource;
    }

    /**
     * Rejects paths with a segment that looks like a Fedora API extension ({@code fcr:...}):
     * such paths address versions, fixity or search, never resources.
     *
     * @param path the normalised path
     * @throws ConstraintViolationException when a segment starts with {@code fcr:}
     */
    static void rejectExtensionSegments(final String path) {
        for (final String segment : path.split("/")) {
            final String decoded = java.net.URLDecoder.decode(segment.replace("+", "%2B"), StandardCharsets.UTF_8);
            if (decoded.regionMatches(true, 0, Fedora.EXTENSION_PREFIX, 0, Fedora.EXTENSION_PREFIX.length())) {
                throw new ConstraintViolationException("Path segments starting with " + Fedora.EXTENSION_PREFIX
                    + " are reserved for API extensions: " + segment);
            }
        }
    }

    // ------------------------------------------------------------------------------------------
    // Patching
    // ------------------------------------------------------------------------------------------

    /**
     * Applies a SPARQL 1.1 Update to an RDF source, container or description (HTTP PATCH).
     *
     * <p>The update runs against the complete current representation, so {@code DELETE WHERE}
     * patterns can match server-managed triples. Afterwards every server-managed triple must still
     * be present unchanged and no new server-managed triple may have been added; otherwise the
     * request fails with 409 and nothing is changed.</p>
     *
     * @param path    the target path
     * @param update  the SPARQL Update request
     * @param ifMatch the {@code If-Match} header, may be {@code null}
     * @param base    the server base of the request
     * @return the patched resource
     */
    @Transactional
    public LdpResource patch(final String path, final String update, final String ifMatch, final String base) {
        final ResolvedTarget target = resolve(path);
        final LdpResource resource = target.resource();
        if (!target.isRdf()) {
            throw new MethodNotAllowedException("Non-RDF sources cannot be patched; patch "
                + target.relativeIri() + "/" + DESCRIPTION_SEGMENT + " instead", target.allowedMethods());
        }
        checkPreconditions(resource, ifMatch, null);
        final List<Triple> current = serverManagedTriples.compute(resource, Preference.DEFAULT);
        final List<Triple> before = new ArrayList<>(resource.getTriples());
        before.addAll(current);
        final Model model = TripleMapper.toModel(before, base);
        try {
            final UpdateRequest request = UpdateFactory.create(update, Iris.resolve(base, resource.getRelativeIri()));
            UpdateAction.execute(request, model);
        } catch (QueryException e) {
            throw new BadRequestException("Invalid SPARQL Update: " + e.getMessage(), e);
        }
        final List<Triple> after = TripleMapper.toTriples(model, base);
        final Set<Triple> afterSet = new HashSet<>(after);
        for (final Triple managed : current) {
            if (!afterSet.contains(managed)) {
                throw new ConstraintViolationException("PATCH must not remove the server-managed triple " + managed);
            }
        }
        final ChangeSet changes = new ChangeSet();
        final String previousMembershipResource = resource.getMembershipResource();
        resource.setTriples(LdpRules.acceptClientTriples(resource, after, current));
        LdpRules.configureMembership(resource);
        resource.touch(agent.name());
        repository.save(resource);
        changes.updated(resource);
        touchMembershipChanges(resource, previousMembershipResource, changes);
        touchParentMembership(resource, changes);
        complete(changes, base);
        return resource;
    }

    // ------------------------------------------------------------------------------------------
    // Deleting
    // ------------------------------------------------------------------------------------------

    /**
     * Deletes a resource and, for containers, everything below it (HTTP DELETE, LDP 5.2.5).
     *
     * @param path    the target path
     * @param ifMatch the {@code If-Match} header, may be {@code null}
     * @param base    the server base of the request
     */
    @Transactional
    public void delete(final String path, final String ifMatch, final String base) {
        final ResolvedTarget target = resolve(path);
        final LdpResource resource = target.resource();
        if (target.description()) {
            throw new MethodNotAllowedException("Delete the non-RDF source " + resource.getRelativeIri()
                + " to remove its description", target.allowedMethods());
        }
        if (resource.isRoot()) {
            throw new MethodNotAllowedException("The root container cannot be deleted", target.allowedMethods());
        }
        checkPreconditions(resource, ifMatch, null);

        final List<LdpResource> doomed = new ArrayList<>(repository.findByPathStartingWith(resource.getPath() + "/"));
        doomed.sort(Comparator.comparingInt((LdpResource r) -> r.getPath().length()).reversed());
        doomed.add(resource);

        final ChangeSet changes = new ChangeSet();
        final Set<String> affectedMembershipResources = new LinkedHashSet<>();
        for (final LdpResource victim : doomed) {
            if (victim.getInteractionModel().isMembershipContainer() && victim.getMembershipResource() != null) {
                affectedMembershipResources.add(victim.getMembershipResource());
            }
            if (victim.isVersioned() || victim.getBinary() != null) {
                final String objectId = victim.getBinary() != null
                    ? victim.getBinary().getStorageKey()
                    : StorageKeys.objectId(victim.getPath());
                afterCommit(() -> purgeQuietly(objectId));
            }
            repository.delete(victim);
            changes.deleted(victim);
        }
        repository.flush();

        final LdpResource container = repository.findByPath(resource.getParentPath())
            .orElseThrow(() -> new IllegalStateException("Parent of " + resource.getPath() + " is missing"));
        container.touch(agent.name());
        repository.save(container);
        changes.updated(container);
        touchMembershipResource(container.getMembershipResource(), container, changes);
        for (final String membershipResource : affectedMembershipResources) {
            touchLocal(membershipResource, changes);
        }
        complete(changes, base);
        log.info("Deleted {} ({} resources)", resource.getRelativeIri(), doomed.size());
    }

    // ------------------------------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------------------------------

    /**
     * Computes the complete representation (client-managed plus every server-managed triple).
     *
     * @param resource   the resource
     * @param preference which optional server-managed triples to include
     * @return the triples, with server-relative IRIs
     */
    List<Triple> representation(final LdpResource resource, final Preference preference) {
        final List<Triple> triples = new ArrayList<>(resource.getTriples());
        triples.addAll(serverManagedTriples.compute(resource, preference));
        return triples;
    }

    private List<Triple> parse(final Payload payload, final String iri, final String base) {
        if (payload == null || payload instanceof Payload.Empty) {
            return List.of();
        }
        if (!(payload instanceof Payload.Rdf rdf)) {
            throw new IllegalArgumentException("Not an RDF payload: " + payload);
        }
        final Model model = ModelFactory.createDefaultModel();
        try {
            RDFDataMgr.read(model, new ByteArrayInputStream(rdf.content()), iri, rdf.lang());
        } catch (RiotException e) {
            throw new BadRequestException("Invalid " + rdf.lang().getName() + ": " + e.getMessage(), e);
        }
        return TripleMapper.toTriples(model, base);
    }

    private static UnsupportedMediaTypeException rdfRequired(final InteractionModel model) {
        return new UnsupportedMediaTypeException(model.getIri() + " requires an RDF body",
            LdpHeaders.ACCEPT_POST, RdfMediaTypes.readableTypesHeaderValue());
    }

    private void checkPreconditions(final LdpResource resource, final String ifMatch, final String ifNoneMatch) {
        final String current = EntityTags.of(resource);
        if (ifMatch != null && !EntityTags.matches(ifMatch, current)) {
            throw new PreconditionFailedException("If-Match " + ifMatch + " does not match the current entity tag " + current);
        }
        if (ifNoneMatch != null && EntityTags.matches(ifNoneMatch, current)) {
            throw new PreconditionFailedException("If-None-Match " + ifNoneMatch + " matches the current entity tag");
        }
    }

    /**
     * Stores the bytes of a non-RDF source as a new version of its OCFL object, together with a
     * snapshot of its description, and points the resource at that version.
     *
     * <p>When the surrounding transaction rolls back, the version is removed again (unless another
     * writer has already added a newer one), so the storage layer never keeps bytes the database
     * does not reference as the current state.</p>
     *
     * @param resource the non-RDF source
     * @param binary   the uploaded bytes
     * @param message  the version message
     */
    private void storeBinary(final LdpResource resource, final Payload.Binary binary, final String message) {
        final String objectId = StorageKeys.objectId(resource.getPath());
        final StoredVersion version;
        try {
            version = binaryStore.write(objectId,
                List.of(new BinaryStore.SourceFile(StorageKeys.CONTENT, binary.upload().file())),
                descriptionSnapshot(resource),
                new BinaryStore.VersionNote(agent.name(), message));
        } catch (IOException e) {
            throw new LdpException(HttpStatus.SERVICE_UNAVAILABLE, "Unable to store binary: " + e.getMessage(),
                new HttpHeaders(), e);
        }
        onRollback(() -> undoWrite(objectId, version.versionId()));
        resource.setBinary(new BinaryContent(objectId, version.versionId(), binary.contentType(), binary.filename(),
            binary.upload().size(), binary.upload().sha256()));
        resource.setVersioned(true);
    }

    /**
     * Serialises the client-managed triples of a resource for the storage layer.
     *
     * @param resource the resource
     * @return the N-Triples snapshot, with IRIs resolved against {@link #SNAPSHOT_BASE}
     */
    static BinaryStore.InlineFile descriptionSnapshot(final LdpResource resource) {
        return new BinaryStore.InlineFile(StorageKeys.DESCRIPTION,
            NTriplesSerializer.serialize(resource.getTriples(), SNAPSHOT_BASE).getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Undoes a version written by a transaction that rolled back: the object returns to the state
     * it had before the write, or disappears when the write created it.
     *
     * <p>Nothing is undone when another writer has added a newer version in the meantime; the
     * unreferenced version then simply stays in the object's history.</p>
     *
     * @param objectId       the object
     * @param writtenVersion the version the transaction wrote
     */
    void undoWrite(final String objectId, final String writtenVersion) {
        try {
            final Optional<StoredVersion> head = binaryStore.head(objectId);
            if (head.isEmpty() || !writtenVersion.equals(head.get().versionId())) {
                return;
            }
            if ("v1".equals(writtenVersion)) {
                binaryStore.purge(objectId);
            } else {
                binaryStore.rollback(objectId, previousOf(writtenVersion));
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Unable to undo version {} of {}; it remains in the object's history", writtenVersion, objectId, e);
        }
    }

    /**
     * Returns the identifier of the version preceding a version.
     *
     * @param versionId a version identifier such as {@code v3}
     * @return the preceding version identifier, for example {@code v2}
     */
    static String previousOf(final String versionId) {
        return "v" + (Long.parseLong(versionId.substring(1)) - 1);
    }

    private void purgeQuietly(final String objectId) {
        try {
            binaryStore.purge(objectId);
        } catch (IOException | RuntimeException e) {
            log.warn("Unable to purge storage object {}; it is now orphaned", objectId, e);
        }
    }

    /** Touches the membership resource of {@code container} when it is a membership container. */
    private void touchMembershipResource(final String membershipResource, final LdpResource container,
            final ChangeSet changes) {
        if (container.getInteractionModel().isMembershipContainer() && membershipResource != null) {
            touchLocal(membershipResource, changes);
        }
    }

    /** Touches old and new membership resources when a container's configuration changed. */
    private void touchMembershipChanges(final LdpResource resource, final String previous, final ChangeSet changes) {
        if (previous != null) {
            touchLocal(previous, changes);
        }
        if (resource.getMembershipResource() != null) {
            touchLocal(resource.getMembershipResource(), changes);
        }
    }

    /**
     * Touches the membership resource of the parent of an indirect container member: the member's
     * content determines membership, so changing it changes the membership resource.
     */
    private void touchParentMembership(final LdpResource resource, final ChangeSet changes) {
        if (resource.getParentPath() == null) {
            return;
        }
        repository.findByPath(resource.getParentPath())
            .filter(parent -> parent.getInteractionModel() == InteractionModel.INDIRECT_CONTAINER)
            .ifPresent(parent -> touchLocal(parent.getMembershipResource(), changes));
    }

    /** Touches a resource hosted by this server, identified by a relative IRI. */
    private void touchLocal(final String relativeIri, final ChangeSet changes) {
        ResourcePaths.fromRelativeIri(relativeIri)
            .flatMap(repository::findByPath)
            .filter(target -> !changes.isDeleted(target))
            .ifPresent(target -> {
                target.touch(agent.name());
                repository.save(target);
                changes.updated(target);
            });
    }

    /** Flushes pending changes (assigning versions) and schedules the events. */
    private void complete(final ChangeSet changes, final String base) {
        repository.flush();
        eventPublisher.publish(changes, base, resource -> representation(resource, Preference.DEFAULT));
    }

    /**
     * Runs an action after the current transaction commits (immediately without a transaction).
     *
     * @param action the action
     */
    static void afterCommit(final Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    /**
     * Runs an action when the current transaction rolls back.
     *
     * @param action the action
     */
    static void onRollback(final Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(final int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        action.run();
                    }
                }
            });
        }
    }

}
