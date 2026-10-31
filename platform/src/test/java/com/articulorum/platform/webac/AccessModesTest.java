package com.articulorum.platform.webac;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import com.articulorum.domain.vocabulary.Acl;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link AccessModes}.
 */
class AccessModesTest {

    @ParameterizedTest
    @CsvSource({
        "GET, books, books, Read",
        "HEAD, books, books, Read",
        "POST, books, books, Append",
        "PUT, books/a, books/a, Write",
        "PATCH, books, books, Write",
        "DELETE, books, books, Write",
        "GET, books/fcr:acl, books, Control",
        "PUT, books/fcr:acl, books, Control",
        "GET, books/fcr:versions, books, Read",
        "POST, books/fcr:versions, books, Write",
        "GET, books/fcr:versions/v1, books, Read",
        "GET, books/a.png/fcr:fixity, books/a.png, Read",
        "GET, fcr:search, '', Read",
        "POST, books/fcr:inbox, books, Append",
        "GET, books/fcr:inbox, books, Read",
        "POST, books/fcr:outbox, books, Write",
    })
    void mapsRequestsToModes(final String method, final String path, final String target, final String mode) {
        assertThat(AccessModes.required(method, path))
            .contains(new AccessModes.Requirement(target, Acl.NAMESPACE + mode));
    }

    @Test
    void optionsAndUnknownExtensionsRequireNothing() {
        assertThat(AccessModes.required("OPTIONS", "books")).isEmpty();
        assertThat(AccessModes.required("GET", "books/fcr:unknown")).isEmpty();
        assertThat(AccessModes.required("TRACE", "books")).isEmpty();
    }

    @Test
    void writeSatisfiesAppend() {
        assertThat(AccessModes.satisfies(Set.of(Acl.WRITE), Acl.APPEND)).isTrue();
        assertThat(AccessModes.satisfies(Set.of(Acl.APPEND), Acl.WRITE)).isFalse();
        assertThat(AccessModes.satisfies(Set.of(Acl.READ), Acl.READ)).isTrue();
    }

}
