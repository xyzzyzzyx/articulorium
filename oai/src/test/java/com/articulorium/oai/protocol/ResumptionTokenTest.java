package com.articulorium.oai.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link ResumptionToken} and {@link OaiIdentifiers}.
 */
class ResumptionTokenTest {

    @Test
    void roundTripsEveryField() {
        final ResumptionToken token = new ResumptionToken(OaiVerb.LIST_RECORDS, "oai_dc", "arts:paintings",
            "2026-01-01", "2026-12-31", Instant.parse("2026-11-05T18:30:15.123456Z"), 3, 200);
        final String encoded = token.encode();

        assertThat(encoded).matches("[A-Za-z0-9_-]+");
        assertThat(ResumptionToken.decode(encoded, OaiVerb.LIST_RECORDS)).isEqualTo(token);
        final ResumptionToken bare = new ResumptionToken(OaiVerb.LIST_SETS, null, null, null, null, null, 100, 100);
        assertThat(ResumptionToken.decode(bare.encode(), OaiVerb.LIST_SETS)).isEqualTo(bare);
    }

    @Test
    void rejectsTokensOfOtherVerbsAndGarbage() {
        final String identifiers = new ResumptionToken(OaiVerb.LIST_IDENTIFIERS, "oai_dc", null, null, null, null, 0, 0)
            .encode();
        for (final String token : new String[] {"not base64!", "AAAA", identifiers}) {
            assertThatThrownBy(() -> ResumptionToken.decode(token, OaiVerb.LIST_RECORDS))
                .isInstanceOfSatisfying(OaiException.class,
                    e -> assertThat(e.getError()).isEqualTo(OaiError.BAD_RESUMPTION_TOKEN));
        }
        final String negative = java.util.Base64.getUrlEncoder().encodeToString(
            "1|ListRecords|oai_dc|||||-1|0".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThatThrownBy(() -> ResumptionToken.decode(negative, OaiVerb.LIST_RECORDS))
            .isInstanceOf(OaiException.class);
    }

    @Test
    void mapsIdentifiersToPaths() {
        final OaiIdentifiers identifiers = new OaiIdentifiers("articulorium.org");

        assertThat(identifiers.identifier("communities/arts/moby")).isEqualTo("oai:articulorium.org:communities/arts/moby");
        assertThat(identifiers.path("oai:articulorium.org:communities/arts/moby")).hasValue("communities/arts/moby");
        assertThat(identifiers.path("oai:other.org:a")).isEmpty();
        assertThat(identifiers.path("oai:articulorium.org:")).isEmpty();
        assertThat(identifiers.path("oai:articulorium.org:/a//b/")).isEmpty();
        assertThat(identifiers.path(null)).isEmpty();
    }

}
