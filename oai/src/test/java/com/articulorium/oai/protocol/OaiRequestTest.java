package com.articulorium.oai.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Tests the argument rules of {@link OaiRequest} (OAI-PMH 2.0 section 3.1 and 4).
 */
class OaiRequestTest {

    @Test
    void parsesEveryVerbWithItsArguments() {
        assertThat(parse("verb", "Identify").verb()).isEqualTo(OaiVerb.IDENTIFY);
        assertThat(parse("verb", "ListMetadataFormats", "identifier", "oai:x.org:a").argument("identifier"))
            .isEqualTo("oai:x.org:a");
        assertThat(parse("verb", "ListSets").arguments()).isEmpty();
        assertThat(parse("verb", "GetRecord", "identifier", "oai:x.org:a", "metadataPrefix", "oai_dc").arguments())
            .containsOnlyKeys("identifier", "metadataPrefix");
        assertThat(parse("verb", "ListIdentifiers", "metadataPrefix", "oai_dc", "from", "2026-01-01", "until",
            "2026-12-31", "set", "arts").arguments()).hasSize(4);
        assertThat(parse("verb", "ListRecords", "resumptionToken", "abc").argument("resumptionToken")).isEqualTo("abc");
    }

    @Test
    void rejectsMissingRepeatedAndUnknownVerbs() {
        assertError(Map.of(), OaiError.BAD_VERB);
        assertError(Map.of("verb", new String[] {"Identify", "Identify"}), OaiError.BAD_VERB);
        assertError(Map.of("verb", new String[] {"identify"}), OaiError.BAD_VERB);
        assertError(Map.of("verb", new String[] {"Bogus"}), OaiError.BAD_VERB);
    }

    @Test
    void rejectsIllegalArguments() {
        // unknown argument
        assertError(params("verb", "Identify", "metadataPrefix", "oai_dc"), OaiError.BAD_ARGUMENT);
        assertError(params("verb", "ListRecords", "metadataPrefix", "oai_dc", "colour", "red"), OaiError.BAD_ARGUMENT);
        // missing required argument
        assertError(params("verb", "GetRecord", "identifier", "oai:x.org:a"), OaiError.BAD_ARGUMENT);
        assertError(params("verb", "ListIdentifiers"), OaiError.BAD_ARGUMENT);
        // resumptionToken is exclusive, and only list verbs have one
        assertError(params("verb", "ListRecords", "resumptionToken", "abc", "metadataPrefix", "oai_dc"),
            OaiError.BAD_ARGUMENT);
        assertError(params("verb", "GetRecord", "resumptionToken", "abc"), OaiError.BAD_ARGUMENT);
        // empty and repeated values
        assertError(params("verb", "ListRecords", "metadataPrefix", " "), OaiError.BAD_ARGUMENT);
        final Map<String, String[]> repeated = params("verb", "ListRecords");
        repeated.put("metadataPrefix", new String[] {"oai_dc", "oai_dc"});
        assertError(repeated, OaiError.BAD_ARGUMENT);
    }

    private static OaiRequest parse(final String... pairs) {
        return OaiRequest.parse(params(pairs));
    }

    private static Map<String, String[]> params(final String... pairs) {
        final Map<String, String[]> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], new String[] {pairs[i + 1]});
        }
        return map;
    }

    private static void assertError(final Map<String, String[]> parameters, final OaiError error) {
        assertThatThrownBy(() -> OaiRequest.parse(parameters))
            .isInstanceOfSatisfying(OaiException.class, e -> assertThat(e.getError()).isEqualTo(error));
    }

}
