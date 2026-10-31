package com.articulorum.platform.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link StorageKeys}.
 */
class StorageKeysTest {

    /** Object identifiers are the normalised resource path under the Articulorum prefix. */
    @Test
    void objectIdPrefixesTheNormalisedPath() {
        assertThat(StorageKeys.objectId("books/cover.png")).isEqualTo("info:articulorum/books/cover.png");
        assertThat(StorageKeys.objectId("/books/cover.png/")).isEqualTo("info:articulorum/books/cover.png");
    }

    /** The root container never owns an object. */
    @ParameterizedTest
    @ValueSource(strings = {"", "/"})
    void rootHasNoObject(final String root) {
        assertThatIllegalArgumentException().isThrownBy(() -> StorageKeys.objectId(root));
    }

    /** Identifiers produced by {@link StorageKeys#objectId} validate. */
    @Test
    void validAcceptsMintedIdentifiers() {
        final String id = StorageKeys.objectId("a/b");

        assertThat(StorageKeys.validate(id)).isSameAs(id);
    }

    /** Foreign, empty and traversal identifiers are rejected. */
    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"info:fedora/a", "info:articulorum/", "info:articulorum/../etc", "info:articulorum/a\0b"})
    void validateRejectsForeignIdentifiers(final String id) {
        assertThatIllegalArgumentException().isThrownBy(() -> StorageKeys.validate(id));
    }

    /** OCFL version identifiers are "v" plus a positive number. */
    @Test
    void recognisesVersionIdentifiers() {
        assertThat(StorageKeys.isVersionId("v1")).isTrue();
        assertThat(StorageKeys.isVersionId("v42")).isTrue();
        assertThat(StorageKeys.isVersionId("v0")).isFalse();
        assertThat(StorageKeys.isVersionId("1")).isFalse();
        assertThat(StorageKeys.isVersionId("v1a")).isFalse();
        assertThat(StorageKeys.isVersionId(null)).isFalse();
    }

}
