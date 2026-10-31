package com.articulorum.platform.service;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import com.articulorum.domain.BinaryContent;
import com.articulorum.domain.LdpResource;
import com.articulorum.domain.Triple;
import com.articulorum.domain.vocabulary.Premis;
import com.articulorum.platform.ldp.exception.LdpException;
import com.articulorum.platform.ldp.exception.MethodNotAllowedException;
import com.articulorum.platform.ldp.exception.ResourceNotFoundException;
import com.articulorum.platform.rdf.TripleMapper;
import com.articulorum.platform.repository.LdpResourceRepository;
import com.articulorum.platform.storage.BinaryNotFoundException;
import com.articulorum.platform.storage.BinaryStore;
import com.articulorum.platform.storage.StorageKeys;
import com.articulorum.platform.storage.StoredVersion;
import com.articulorum.platform.storage.ValidationReport;

import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RiotException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.extern.slf4j.Slf4j;

/**
 * Versions (Memento) and fixity of LDP resources, backed by the OCFL storage layer.
 *
 * <p>Every resource may have an OCFL object holding its history. A non-RDF source gets one on
 * creation, and each replacement of its bytes adds a version. Any other (non-root) resource gets
 * one when a client first asks for a version ({@code POST <resource>/fcr:versions}). Each version
 * holds a snapshot of the resource's client-managed triples ({@link StorageKeys#DESCRIPTION}) and,
 * for non-RDF sources, its bytes ({@link StorageKeys#CONTENT}). A version is exposed as a
 * <em>memento</em> of the resource, identified by its OCFL version identifier ({@code v1},
 * {@code v2}, ...).</p>
 *
 * <p>Each version of a binary also records the binary's technical metadata at that time
 * ({@link StorageKeys#TECHNICAL}: size, digest, media type, file name), so its memento describes
 * the bytes it holds rather than the current ones.</p>
 *
 * <p>Resources inside an <em>archival group</em> share the group's object (see
 * {@link StorageLocator}): the group is versioned as a whole, its members list the group's
 * versions that contain them, and versions cannot be created for members individually.</p>
 */
@Slf4j
@Service
public class HistoryService {

    /**
     * A historic state of a resource.
     *
     * @param resource    the current resource the memento belongs to
     * @param version     the version
     * @param triples     the client-managed triples at that version, with server-relative IRIs
     * @param technical   the technical metadata of a binary at that version (size, digest, media
     *                    type, file name), empty for other resources
     * @param contentPath the logical path of the binary's bytes inside the object
     */
    public record Memento(LdpResource resource, StoredVersion version, List<Triple> triples, List<Triple> technical,
            String contentPath) {

        /**
         * Creates a memento of a resource that owns its object.
         *
         * @param resource the current resource
         * @param version  the version
         * @param triples  the client-managed triples at that version
         */
        public Memento(final LdpResource resource, final StoredVersion version, final List<Triple> triples) {
            this(resource, version, triples, List.of(), StorageKeys.CONTENT);
        }

        /** @return {@code true} when the memento holds bytes (a version of a non-RDF source) */
        public boolean hasContent() {
            return version.contains(contentPath);
        }

        /**
         * Returns the media type the binary had at this version.
         *
         * @return the historic media type, or {@code null} when it was not recorded
         */
        public String contentType() {
            return technical.stream()
                .filter(triple -> Premis.HAS_MIME_TYPE.equals(triple.getPredicate()))
                .map(Triple::getObject)
                .findFirst()
                .orElse(null);
        }

    }

    /**
     * Outcome of a fixity check of a non-RDF source.
     *
     * @param resource       the server-relative IRI of the checked resource
     * @param status         {@code SUCCESS} when the bytes match the recorded size and digest and
     *                       the OCFL object is valid, {@code FAILURE} otherwise
     * @param storageVersion the OCFL version that was checked
     * @param expectedSize   the recorded size
     * @param actualSize     the size of the stored bytes
     * @param expectedSha256 the recorded SHA-256 digest
     * @param actualSha256   the SHA-256 digest of the stored bytes
     * @param storageValid   whether the OCFL object validated, including its content digests
     * @param storageErrors  validation errors reported by the OCFL layer
     * @param checked        when the check ran
     */
    public record FixityReport(String resource, String status, String storageVersion, Long expectedSize,
            long actualSize, String expectedSha256, String actualSha256, boolean storageValid,
            List<String> storageErrors, Instant checked) {

        /** Status of a successful check. */
        public static final String SUCCESS = "SUCCESS";

        /** Status of a failed check. */
        public static final String FAILURE = "FAILURE";

    }

    private final LdpService ldpService;

    private final LdpResourceRepository repository;

    private final BinaryStore store;

    private final CurrentAgent agent;

    private final StorageLocator locator;

    /**
     * Creates the service.
     *
     * @param ldpService the LDP service (resolves request paths)
     * @param repository the resource repository
     * @param store      the OCFL storage layer
     * @param agent      determines the agent creating versions
     * @param locator    locates resources in the storage layer (archival groups)
     */
    public HistoryService(final LdpService ldpService, final LdpResourceRepository repository,
            final BinaryStore store, final CurrentAgent agent, final StorageLocator locator) {
        this.ldpService = ldpService;
        this.repository = repository;
        this.store = store;
        this.agent = agent;
        this.locator = locator;
    }

    /**
     * Resolves the resource whose history a path addresses; a binary's description shares the
     * binary's history.
     *
     * @param path the resource path
     * @return the resource
     * @throws ResourceNotFoundException when nothing exists at the path
     */
    @Transactional(readOnly = true)
    public LdpResource resolve(final String path) {
        return ldpService.resolve(path).resource();
    }

    /**
     * Lists the versions of a resource, oldest first.
     *
     * @param path the resource path
     * @return the versions; empty when none were created
     */
    @Transactional(readOnly = true)
    public List<StoredVersion> versions(final String path) {
        final LdpResource resource = resolve(path);
        if (!resource.isVersioned()) {
            return List.of();
        }
        final StorageLocator.Location location = locator.locate(resource);
        try {
            final List<StoredVersion> versions = store.versions(location.objectId());
            if (!location.isGroupMember()) {
                return versions;
            }
            return versions.stream().filter(version -> containsResource(version, location)).toList();
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    /**
     * Tells whether a version of an object holds the state of a resource.
     *
     * @param version  the version
     * @param location the resource's location
     * @return {@code true} when the version contains its description or bytes
     */
    static boolean containsResource(final StoredVersion version, final StorageLocator.Location location) {
        return version.contains(location.descriptionPath()) || version.contains(location.contentPath());
    }

    /**
     * Records the current state of a resource as a new version.
     *
     * @param path the resource path
     * @return the new version
     * @throws MethodNotAllowedException for the root container, which is not versioned
     */
    @Transactional
    public StoredVersion createVersion(final String path) {
        final LdpResource resource = resolve(path);
        if (resource.isRoot()) {
            throw new MethodNotAllowedException("The root container is not versioned",
                java.util.Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS));
        }
        final StorageLocator.Location location = locator.locate(resource);
        if (location.isGroupMember()) {
            throw new MethodNotAllowedException("Versions of " + resource.getRelativeIri()
                + " are created by versioning its archival group " + location.group().getRelativeIri(),
                java.util.Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS));
        }
        final String objectId = location.objectId();
        final StoredVersion version;
        try {
            version = store.write(objectId, new BinaryStore.VersionChanges(List.of(), snapshots(resource, location),
                    Set.of()),
                new BinaryStore.VersionNote(agent.name(), "Create version of " + resource.getRelativeIri()));
        } catch (IOException e) {
            throw unavailable(e);
        }
        LdpService.onRollback(() -> ldpService.undoWrite(objectId, version.versionId()));
        if (!resource.isVersioned()) {
            resource.setVersioned(true);
            repository.save(resource);
        }
        log.info("Created version {} of {}", version.versionId(), resource.getRelativeIri());
        return version;
    }

    /**
     * Builds the snapshots a new version records: the resource's description and, for a binary,
     * its technical metadata; for an archival group the same for every resource below it.
     *
     * @param resource the versioned resource
     * @param location its location
     * @return the inline files to write
     */
    List<BinaryStore.InlineFile> snapshots(final LdpResource resource, final StorageLocator.Location location) {
        final List<BinaryStore.InlineFile> files = new ArrayList<>();
        addSnapshots(files, resource, "");
        if (location.isGroup()) {
            for (final LdpResource member : repository.findByPathStartingWith(resource.getPath() + "/")) {
                addSnapshots(files, member, StorageKeys.relativePath(resource.getPath(), member.getPath()));
            }
        }
        return files;
    }

    private static void addSnapshots(final List<BinaryStore.InlineFile> files, final LdpResource resource,
            final String relativePath) {
        files.add(LdpService.descriptionSnapshot(resource, StorageKeys.descriptionPath(relativePath)));
        if (resource.isNonRdfSource() && resource.getBinary() != null) {
            files.add(LdpService.technicalSnapshot(resource, resource.getBinary(),
                StorageKeys.technicalPath(relativePath)));
        }
    }

    /**
     * Returns a memento of a resource.
     *
     * @param path      the resource path
     * @param versionId the version identifier
     * @return the memento
     * @throws ResourceNotFoundException when the resource or version does not exist
     */
    @Transactional(readOnly = true)
    public Memento memento(final String path, final String versionId) {
        final LdpResource resource = resolve(path);
        if (!resource.isVersioned() || !StorageKeys.isVersionId(versionId)) {
            throw new ResourceNotFoundException(resource.getPath() + "/fcr:versions/" + versionId);
        }
        final StorageLocator.Location location = locator.locate(resource);
        final String objectId = location.objectId();
        try {
            final StoredVersion version = store.versions(objectId).stream()
                .filter(candidate -> candidate.versionId().equals(versionId))
                .filter(candidate -> !location.isGroupMember() || containsResource(candidate, location))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(resource.getPath() + "/fcr:versions/" + versionId));
            final List<Triple> technical = version.contains(location.contentPath())
                ? technicalAt(objectId, version, location, resource)
                : List.of();
            return new Memento(resource, version, readSnapshot(objectId, version, location.descriptionPath()),
                technical, location.contentPath());
        } catch (IOException e) {
            throw unavailable(e);
        }
    }

    /**
     * Opens the bytes a memento of a non-RDF source holds.
     *
     * @param memento the memento
     * @return a stream the caller closes
     * @throws IOException when the storage layer fails
     */
    public InputStream openContent(final Memento memento) throws IOException {
        if (!memento.hasContent()) {
            throw new ResourceNotFoundException(memento.resource().getPath());
        }
        return store.open(objectIdOf(memento.resource()), memento.version().versionId(), memento.contentPath());
    }

    /**
     * Checks the fixity of a non-RDF source: re-computes the size and SHA-256 digest of its current
     * bytes, compares them with the recorded values and validates the OCFL object.
     *
     * @param path the path of the non-RDF source (or of its description)
     * @return the report
     * @throws MethodNotAllowedException when the resource is not a non-RDF source
     */
    @Transactional(readOnly = true)
    public FixityReport fixity(final String path) {
        final LdpResource resource = resolve(path);
        final BinaryContent binary = resource.getBinary();
        if (!resource.isNonRdfSource() || binary == null) {
            throw new MethodNotAllowedException("Fixity can only be checked for non-RDF sources",
                java.util.Set.of(HttpMethod.OPTIONS));
        }
        final MessageDigest digest = sha256();
        long size = 0;
        try (InputStream in = new DigestInputStream(
                store.open(binary.getStorageKey(), binary.getStorageVersion(), binary.contentPath()), digest)) {
            final byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                size += read;
            }
            final ValidationReport validation = store.validate(binary.getStorageKey());
            final String actual = HexFormat.of().formatHex(digest.digest());
            final boolean matches = Objects.equals(binary.getSize(), size) && actual.equalsIgnoreCase(binary.getSha256());
            return new FixityReport(resource.getRelativeIri(),
                matches && validation.valid() ? FixityReport.SUCCESS : FixityReport.FAILURE,
                binary.getStorageVersion(), binary.getSize(), size, binary.getSha256(), actual, validation.valid(),
                validation.errors(), Instant.now());
        } catch (BinaryNotFoundException e) {
            return new FixityReport(resource.getRelativeIri(), FixityReport.FAILURE, binary.getStorageVersion(),
                binary.getSize(), 0, binary.getSha256(), null, false, List.of(e.getMessage()), Instant.now());
        } catch (IOException e) {
            throw unavailable(e);
        } catch (RuntimeException e) {
            // ocfl-java reports a digest mismatch while streaming as an unchecked exception
            return new FixityReport(resource.getRelativeIri(), FixityReport.FAILURE, binary.getStorageVersion(),
                binary.getSize(), size, binary.getSha256(), null, false, List.of(String.valueOf(e.getMessage())),
                Instant.now());
        }
    }

    /**
     * Returns the identifier of the OCFL object holding a resource's history.
     *
     * @param resource the resource
     * @return the object identifier
     */
    String objectIdOf(final LdpResource resource) {
        return resource.getBinary() != null && resource.getBinary().getStorageKey() != null
            ? resource.getBinary().getStorageKey()
            : locator.locate(resource).objectId();
    }

    /**
     * Returns the logical path of a resource's bytes inside its storage object.
     *
     * @param resource the resource
     * @return the logical path (meaningful for binaries only)
     */
    @Transactional(readOnly = true)
    public String contentPathOf(final LdpResource resource) {
        return locator.locate(resource).contentPath();
    }

    /**
     * Finds the archival group a resource belongs to (possibly itself).
     *
     * @param resource the resource
     * @return the archival group, or empty
     */
    @Transactional(readOnly = true)
    public java.util.Optional<LdpResource> archivalGroupOf(final LdpResource resource) {
        return locator.archivalGroupOf(resource);
    }

    /**
     * Reads the technical metadata a version recorded for a binary. Versions written before
     * technical snapshots existed fall back to the digest OCFL itself recorded for the bytes.
     *
     * @param objectId the object
     * @param version  the version
     * @param location the binary's location
     * @param resource the binary
     * @return the triples, with server-relative IRIs
     * @throws IOException when the storage layer fails
     */
    private List<Triple> technicalAt(final String objectId, final StoredVersion version,
            final StorageLocator.Location location, final LdpResource resource) throws IOException {
        if (version.contains(location.technicalPath())) {
            return readSnapshot(objectId, version, location.technicalPath());
        }
        final String digest = version.files().get(location.contentPath());
        if (digest == null || digest.isBlank()) {
            return List.of();
        }
        return List.of(Triple.iri(resource.getRelativeIri(), Premis.HAS_MESSAGE_DIGEST, SHA512_URN_PREFIX + digest));
    }

    /** Prefix of the SHA-512 digest IRIs derived from OCFL inventories. */
    static final String SHA512_URN_PREFIX = "urn:sha-512:";

    private List<Triple> readSnapshot(final String objectId, final StoredVersion version, final String logicalPath)
            throws IOException {
        if (!version.contains(logicalPath)) {
            return List.of();
        }
        final Model model = ModelFactory.createDefaultModel();
        try (InputStream in = store.open(objectId, version.versionId(), logicalPath)) {
            RDFDataMgr.read(model, in, LdpService.SNAPSHOT_BASE + "/", Lang.NTRIPLES);
        } catch (RiotException e) {
            throw new IOException("Corrupt description snapshot in " + objectId + "@" + version.versionId(), e);
        }
        return TripleMapper.toTriples(model, LdpService.SNAPSHOT_BASE);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static LdpException unavailable(final IOException e) {
        if (e instanceof BinaryNotFoundException) {
            return new ResourceNotFoundException(e.getMessage());
        }
        return new LdpException(HttpStatus.SERVICE_UNAVAILABLE, "Storage layer unavailable: " + e.getMessage(),
            new HttpHeaders(), e);
    }

}
