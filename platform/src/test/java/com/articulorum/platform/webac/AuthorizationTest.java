package com.articulorum.platform.webac;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import com.articulorum.domain.Triple;
import com.articulorum.domain.vocabulary.Acl;
import com.articulorum.domain.vocabulary.Rdf;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link Authorization}.
 */
class AuthorizationTest {

    @Test
    void parsesAuthorizationsAndIgnoresOtherSubjects() {
        final List<Triple> triples = List.of(
            Triple.iri("/a/fcr:acl#r", Rdf.TYPE, Acl.AUTHORIZATION),
            Triple.iri("/a/fcr:acl#r", Acl.AGENT_CLASS, Acl.FOAF_AGENT),
            Triple.iri("/a/fcr:acl#r", Acl.ACCESS_TO, "/a"),
            Triple.iri("/a/fcr:acl#r", Acl.DEFAULT, "/a"),
            Triple.iri("/a/fcr:acl#r", Acl.MODE, Acl.READ),
            Triple.iri("/a/fcr:acl#w", Rdf.TYPE, Acl.AUTHORIZATION),
            Triple.iri("/a/fcr:acl#w", Acl.AGENT, "https://example.org/alice"),
            Triple.iri("/a/fcr:acl#w", Acl.MODE, Acl.WRITE),
            Triple.iri("/a/fcr:acl#other", Acl.MODE, Acl.CONTROL),
            Triple.literal("/a/fcr:acl#r", "http://purl.org/dc/terms/title", "ignored"));

        final List<Authorization> authorizations = Authorization.parse(triples);

        assertThat(authorizations).extracting(Authorization::id).containsExactly("/a/fcr:acl#r", "/a/fcr:acl#w");
        assertThat(authorizations.get(0).agentClasses()).containsExactly(Acl.FOAF_AGENT);
        assertThat(authorizations.get(0).accessTo()).containsExactly("/a");
        assertThat(authorizations.get(0).defaults()).containsExactly("/a");
        assertThat(authorizations.get(1).agents()).containsExactly("https://example.org/alice");
    }

    @Test
    void writeImpliesAppend() {
        final Authorization write = Authorization.parse(List.of(
            Triple.iri("#w", Rdf.TYPE, Acl.AUTHORIZATION), Triple.iri("#w", Acl.MODE, Acl.WRITE))).get(0);

        assertThat(write.grants(Acl.WRITE)).isTrue();
        assertThat(write.grants(Acl.APPEND)).isTrue();
        assertThat(write.grants(Acl.READ)).isFalse();
        assertThat(write.grants(Acl.CONTROL)).isFalse();
    }

}
