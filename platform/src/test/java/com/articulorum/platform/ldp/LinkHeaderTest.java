package com.articulorum.platform.ldp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import com.articulorum.domain.vocabulary.Ldp;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link LinkHeader}.
 */
class LinkHeaderTest {

    @Test
    void formatsTypeLink() {
        assertEquals("<" + Ldp.RESOURCE + ">; rel=\"type\"", LinkHeader.format(Ldp.RESOURCE, "type"));
    }

    @Test
    void parsesSingleLink() {
        final List<LinkHeader> links = LinkHeader.parse(List.of("<" + Ldp.BASIC_CONTAINER + ">; rel=\"type\""));
        assertEquals(List.of(new LinkHeader(Ldp.BASIC_CONTAINER, Set.of("type"))), links);
    }

    @Test
    void parsesSeveralLinksInOneHeaderAndAcrossHeaders() {
        final List<String> targets = LinkHeader.targets(List.of(
            "<" + Ldp.RESOURCE + ">;rel=type, <" + Ldp.NON_RDF_SOURCE + ">; rel=\"type\"",
            "<http://example.org/acl>; rel=\"acl\""), "type");
        assertEquals(List.of(Ldp.RESOURCE, Ldp.NON_RDF_SOURCE), targets);
    }

    @Test
    void relationNamesAreCaseInsensitiveAndMayBeLists() {
        final List<LinkHeader> links = LinkHeader.parse(List.of("<http://x/a>; rel=\"TYPE describedby\""));
        assertTrue(links.get(0).relations().contains("type"));
        assertTrue(links.get(0).relations().contains("describedby"));
    }

    @Test
    void keepsExtensionRelationsVerbatim() {
        final List<LinkHeader> links = LinkHeader.parse(List.of("<http://x/c>; rel=\"" + Ldp.CONSTRAINED_BY + "\""));
        assertTrue(links.get(0).relations().contains(Ldp.CONSTRAINED_BY));
    }

    @Test
    void commasInsideTargetsDoNotSplitLinks() {
        final List<LinkHeader> links = LinkHeader.parse(List.of("<http://x/a,b>; rel=type"));
        assertEquals("http://x/a,b", links.get(0).target());
    }

    @Test
    void ignoresMalformedEntriesAndNulls() {
        assertEquals(List.of(), LinkHeader.parse(null));
        assertEquals(List.of(), LinkHeader.parse(List.of("not a link", "")));
    }

}
