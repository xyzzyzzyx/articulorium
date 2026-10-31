package com.articulorum.platform.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import io.ocfl.api.OcflRepository;
import io.ocfl.core.OcflRepositoryBuilder;
import io.ocfl.core.extension.storage.layout.config.HashedNTupleLayoutConfig;
import io.ocfl.core.storage.OcflStorage;
import io.ocfl.core.storage.OcflStorageBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests {@link OcflBinaryStore} against real ocfl-java repositories: one on the local file
 * system and one in S3 (an in-memory S3 behind {@link S3OcflCloudClient}), so both storage roots
 * the platform supports are covered by the same contract.
 */
class OcflBinaryStoreTest {

    private static final String OBJECT = StorageKeys.objectId("books/cover.png");

    @TempDir
    private Path temp;

    private OcflRepository repository;

    /**
     * The storage roots under test.
     *
     * @return named storage factories
     */
    static Stream<Arguments> storages() {
        return Stream.of(
            Arguments.of("filesystem", (StorageFactory) dir -> {
                final Path root = Files.createDirectories(dir.resolve("root"));
                return OcflStorageBuilder.builder().fileSystem(root).build();
            }),
            Arguments.of("s3", (StorageFactory) dir -> OcflStorageBuilder.builder()
                .cloud(new S3OcflCloudClient(new InMemoryS3Client("bucket", true), "bucket", "ocfl"))
                .build()));
    }

    /** Builds an OCFL storage root inside a directory. */
    @FunctionalInterface
    interface StorageFactory {
        OcflStorage create(Path directory) throws IOException;
    }

    private OcflBinaryStore store(final StorageFactory factory) throws IOException {
        final Path work = Files.createDirectories(temp.resolve("work"));
        repository = new OcflRepositoryBuilder()
            .defaultLayoutConfig(new HashedNTupleLayoutConfig())
            .storage(factory.create(temp))
            .workDir(work)
            .build();
        return new OcflBinaryStore(repository, "test");
    }

    @AfterEach
    void closeRepository() {
        if (repository != null) {
            repository.close();
        }
    }

    private Path file(final String name, final String content) throws IOException {
        return Files.writeString(temp.resolve(name), content);
    }

    private static String read(final InputStream in) throws IOException {
        try (in) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * Each write creates a new version; untouched files carry over and old versions stay
     * readable.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    void writesImmutableVersions(final String name, final StorageFactory factory) throws IOException {
        final OcflBinaryStore store = store(factory);

        final StoredVersion v1 = store.write(OBJECT,
            List.of(new BinaryStore.SourceFile(StorageKeys.CONTENT, file("a", "first"))),
            new BinaryStore.InlineFile(StorageKeys.DESCRIPTION, "<a> <b> <c> .\n".getBytes(StandardCharsets.UTF_8)),
            new BinaryStore.VersionNote("alice", "create"));
        final StoredVersion v2 = store.write(OBJECT,
            List.of(new BinaryStore.SourceFile(StorageKeys.CONTENT, file("b", "second"))), null,
            new BinaryStore.VersionNote("bob", "replace"));

        assertThat(v1.versionId()).isEqualTo("v1");
        assertThat(v1.agent()).isEqualTo("alice");
        assertThat(v1.message()).isEqualTo("create");
        assertThat(v1.contains(StorageKeys.CONTENT)).isTrue();
        assertThat(v2.versionId()).isEqualTo("v2");
        assertThat(v2.contains(StorageKeys.DESCRIPTION)).as("unchanged files carry over").isTrue();
        assertThat(v2.files().get(StorageKeys.CONTENT)).isNotEqualTo(v1.files().get(StorageKeys.CONTENT));
        assertThat(read(store.open(OBJECT, "v1", StorageKeys.CONTENT))).isEqualTo("first");
        assertThat(read(store.open(OBJECT, null, StorageKeys.CONTENT))).isEqualTo("second");
        assertThat(read(store.open(OBJECT, "v2", StorageKeys.DESCRIPTION))).isEqualTo("<a> <b> <c> .\n");
        assertThat(store.versions(OBJECT)).extracting(StoredVersion::versionId).containsExactly("v1", "v2");
        assertThat(store.head(OBJECT)).map(StoredVersion::versionId).hasValue("v2");
        assertThat(store.exists(OBJECT)).isTrue();
    }

    /** Rolling back discards later versions, which is how failed transactions are undone. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    void rollsBackToAnEarlierVersion(final String name, final StorageFactory factory) throws IOException {
        final OcflBinaryStore store = store(factory);
        store.write(OBJECT, List.of(new BinaryStore.SourceFile(StorageKeys.CONTENT, file("a", "first"))), null, null);
        store.write(OBJECT, List.of(new BinaryStore.SourceFile(StorageKeys.CONTENT, file("b", "second"))), null, null);

        store.rollback(OBJECT, "v1");

        assertThat(store.versions(OBJECT)).extracting(StoredVersion::versionId).containsExactly("v1");
        assertThat(read(store.open(OBJECT, null, StorageKeys.CONTENT))).isEqualTo("first");
    }

    /** Purged objects disappear entirely, and purging twice is harmless. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    void purgesObjects(final String name, final StorageFactory factory) throws IOException {
        final OcflBinaryStore store = store(factory);
        store.write(OBJECT, List.of(new BinaryStore.SourceFile(StorageKeys.CONTENT, file("a", "x"))), null, null);

        store.purge(OBJECT);
        store.purge(OBJECT);

        assertThat(store.exists(OBJECT)).isFalse();
        assertThat(store.versions(OBJECT)).isEmpty();
        assertThat(store.head(OBJECT)).isEmpty();
    }

    /** Missing objects, versions and files, and malformed versions, are "not found". */
    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    void reportsMissingContentAsNotFound(final String name, final StorageFactory factory) throws IOException {
        final OcflBinaryStore store = store(factory);
        assertThatThrownBy(() -> store.open(OBJECT, null, StorageKeys.CONTENT))
            .isInstanceOf(BinaryNotFoundException.class);

        store.write(OBJECT, List.of(new BinaryStore.SourceFile(StorageKeys.CONTENT, file("a", "x"))), null, null);

        assertThatThrownBy(() -> store.open(OBJECT, null, StorageKeys.DESCRIPTION))
            .isInstanceOf(BinaryNotFoundException.class);
        assertThatThrownBy(() -> store.open(OBJECT, "v9", StorageKeys.CONTENT))
            .isInstanceOf(BinaryNotFoundException.class);
        assertThatThrownBy(() -> store.open(OBJECT, "latest", StorageKeys.CONTENT))
            .isInstanceOf(BinaryNotFoundException.class);
        assertThatThrownBy(() -> store.validate(StorageKeys.objectId("other")))
            .isInstanceOf(BinaryNotFoundException.class);
    }

    /** Freshly written objects pass OCFL validation including fixity. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    void validatesObjects(final String name, final StorageFactory factory) throws IOException {
        final OcflBinaryStore store = store(factory);
        store.write(OBJECT, List.of(new BinaryStore.SourceFile(StorageKeys.CONTENT, file("a", "x"))), null, null);

        final ValidationReport report = store.validate(OBJECT);

        assertThat(report.valid()).isTrue();
        assertThat(report.errors()).isEmpty();
    }

    /** Identifiers that the platform did not mint are refused before touching storage. */
    @ParameterizedTest(name = "{0}")
    @MethodSource("storages")
    void rejectsForeignIdentifiers(final String name, final StorageFactory factory) throws IOException {
        final OcflBinaryStore store = store(factory);

        assertThatThrownBy(() -> store.exists("info:fedora/x")).isInstanceOf(IllegalArgumentException.class);
        assertThat(store.describe()).isEqualTo("test");
    }

}
