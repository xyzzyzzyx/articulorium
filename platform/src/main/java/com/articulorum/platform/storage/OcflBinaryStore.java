package com.articulorum.platform.storage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import io.ocfl.api.DigestAlgorithmRegistry;
import io.ocfl.api.OcflOption;
import io.ocfl.api.OcflRepository;
import io.ocfl.api.exception.NotFoundException;
import io.ocfl.api.exception.OcflJavaException;
import io.ocfl.api.model.DigestAlgorithm;
import io.ocfl.api.model.FileDetails;
import io.ocfl.api.model.ObjectVersionId;
import io.ocfl.api.model.OcflObjectVersion;
import io.ocfl.api.model.OcflObjectVersionFile;
import io.ocfl.api.model.ValidationIssue;
import io.ocfl.api.model.ValidationResults;
import io.ocfl.api.model.VersionDetails;
import io.ocfl.api.model.VersionInfo;
import io.ocfl.api.model.VersionNum;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link BinaryStore} backed by an OCFL repository (ocfl-java).
 *
 * <p>The repository may store its objects on the local file system or in S3 compatible object
 * storage; this class is agnostic of that choice (see {@link StorageConfiguration}). ocfl-java
 * failures, which are unchecked, are translated into {@link IOException}s.</p>
 */
@Slf4j
public class OcflBinaryStore implements BinaryStore {

    private final OcflRepository repository;

    private final String description;

    /**
     * Creates the store.
     *
     * @param repository  the OCFL repository; the caller owns (and closes) it
     * @param description a short description of the storage root, for logs and health details
     */
    public OcflBinaryStore(final OcflRepository repository, final String description) {
        this.repository = repository;
        this.description = description;
        log.info("Storage layer: OCFL repository at {}", description);
    }

    @Override
    public StoredVersion write(final String objectId, final VersionChanges changes, final VersionNote note)
            throws IOException {
        StorageKeys.validate(objectId);
        final VersionInfo info = new VersionInfo();
        if (note != null && note.agent() != null) {
            info.setUser(note.agent(), null);
        }
        if (note != null && note.message() != null) {
            info.setMessage(note.message());
        }
        try {
            final ObjectVersionId written = repository.updateObject(ObjectVersionId.head(objectId), info, updater -> {
                for (final String removed : changes.removed()) {
                    updater.removeFile(removed);
                }
                for (final SourceFile file : changes.files()) {
                    updater.addPath(file.source(), file.logicalPath(), OcflOption.OVERWRITE);
                }
                for (final InlineFile inline : changes.inline()) {
                    updater.writeFile(new ByteArrayInputStream(inline.content()), inline.logicalPath(),
                        OcflOption.OVERWRITE);
                }
            });
            return toStoredVersion(repository.describeVersion(written));
        } catch (OcflJavaException e) {
            throw new IOException("Unable to write a version of " + objectId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public InputStream open(final String objectId, final String versionId, final String logicalPath)
            throws IOException {
        StorageKeys.validate(objectId);
        try {
            final OcflObjectVersion version = repository.getObject(versionIdOf(objectId, versionId));
            final OcflObjectVersionFile file = version.getFile(logicalPath);
            if (file == null) {
                throw new BinaryNotFoundException(objectId + "@" + version.getVersionNum() + "/" + logicalPath);
            }
            return file.getStream();
        } catch (NotFoundException e) {
            throw new BinaryNotFoundException(objectId + (versionId == null ? "" : "@" + versionId));
        } catch (OcflJavaException e) {
            throw new IOException("Unable to read " + objectId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public List<StoredVersion> versions(final String objectId) throws IOException {
        StorageKeys.validate(objectId);
        try {
            if (!repository.containsObject(objectId)) {
                return List.of();
            }
            return repository.describeObject(objectId).getVersionMap().values().stream()
                .sorted(Comparator.comparing(VersionDetails::getVersionNum))
                .map(OcflBinaryStore::toStoredVersion)
                .toList();
        } catch (NotFoundException e) {
            return List.of();
        } catch (OcflJavaException e) {
            throw new IOException("Unable to list the versions of " + objectId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public Optional<StoredVersion> head(final String objectId) throws IOException {
        StorageKeys.validate(objectId);
        try {
            if (!repository.containsObject(objectId)) {
                return Optional.empty();
            }
            return Optional.of(toStoredVersion(repository.describeObject(objectId).getHeadVersion()));
        } catch (NotFoundException e) {
            return Optional.empty();
        } catch (OcflJavaException e) {
            throw new IOException("Unable to describe " + objectId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void rollback(final String objectId, final String versionId) throws IOException {
        StorageKeys.validate(objectId);
        try {
            repository.rollbackToVersion(ObjectVersionId.version(objectId, versionId));
        } catch (NotFoundException e) {
            throw new BinaryNotFoundException(objectId + "@" + versionId);
        } catch (OcflJavaException e) {
            throw new IOException("Unable to roll " + objectId + " back to " + versionId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void purge(final String objectId) throws IOException {
        StorageKeys.validate(objectId);
        try {
            repository.purgeObject(objectId);
        } catch (NotFoundException e) {
            // already gone
        } catch (OcflJavaException e) {
            throw new IOException("Unable to purge " + objectId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public boolean exists(final String objectId) throws IOException {
        StorageKeys.validate(objectId);
        try {
            return repository.containsObject(objectId);
        } catch (OcflJavaException e) {
            throw new IOException("Unable to query " + objectId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public ValidationReport validate(final String objectId) throws IOException {
        StorageKeys.validate(objectId);
        try {
            if (!repository.containsObject(objectId)) {
                throw new BinaryNotFoundException(objectId);
            }
            final ValidationResults results = repository.validateObject(objectId, true);
            return new ValidationReport(!results.hasErrors(), messages(results.getErrors()),
                messages(results.getWarnings()));
        } catch (OcflJavaException e) {
            throw new IOException("Unable to validate " + objectId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public String describe() {
        return description;
    }

    /**
     * Builds the OCFL identifier of an object version.
     *
     * @param objectId  the object identifier
     * @param versionId the version identifier, or {@code null} for the head
     * @return the OCFL identifier
     * @throws BinaryNotFoundException when the version identifier is malformed
     */
    static ObjectVersionId versionIdOf(final String objectId, final String versionId) throws BinaryNotFoundException {
        if (versionId == null) {
            return ObjectVersionId.head(objectId);
        }
        if (!StorageKeys.isVersionId(versionId)) {
            throw new BinaryNotFoundException(objectId + "@" + versionId);
        }
        return ObjectVersionId.version(objectId, VersionNum.fromString(versionId));
    }

    /**
     * Converts ocfl-java version details into a {@link StoredVersion}.
     *
     * @param details the version details
     * @return the stored version
     */
    static StoredVersion toStoredVersion(final VersionDetails details) {
        final Map<String, String> files = new LinkedHashMap<>();
        for (final FileDetails file : details.getFiles()) {
            files.put(file.getPath(), digestOf(file.getFixity()));
        }
        final VersionInfo info = details.getVersionInfo();
        return new StoredVersion(details.getVersionNum().toString(),
            details.getCreated() == null ? null : details.getCreated().toInstant(),
            info == null || info.getUser() == null ? null : info.getUser().getName(),
            info == null ? null : info.getMessage(),
            Map.copyOf(files));
    }

    private static String digestOf(final Map<DigestAlgorithm, String> fixity) {
        if (fixity == null || fixity.isEmpty()) {
            return "";
        }
        final String sha512 = fixity.get(DigestAlgorithmRegistry.sha512);
        return sha512 != null ? sha512 : fixity.values().iterator().next();
    }

    private static List<String> messages(final List<ValidationIssue> issues) {
        return issues.stream().map(issue -> issue.getCode() + ": " + issue.getMessage()).toList();
    }

}
