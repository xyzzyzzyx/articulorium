package com.articulorium.activitystreams;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Unit tests for {@link ActivityStreamsValidator}.
 */
class ActivityStreamsValidatorTest {

    private static final String CONTEXT = "\"@context\": \"https://www.w3.org/ns/activitystreams\"";

    private final ActivityStreamsJson json = new ActivityStreamsJson();

    private static byte[] example(final String name) throws IOException {
        try (InputStream in = ActivityStreamsValidatorTest.class
                .getResourceAsStream("/examples/" + name)) {
            return in.readAllBytes();
        }
    }

    private List<Violation> strict(final String document) {
        return ActivityStreamsValidator.strict().validate(json.read(document));
    }

    private List<String> described(final String document) {
        return strict(document).stream().map(Violation::toString).toList();
    }

    @ParameterizedTest
    @ValueSource(strings = {"create-note.json", "offer-review.json", "ordered-collection.json",
        "question.json", "place.json", "link.json", "extended-context.json"})
    void acceptsValidDocuments(final String name) throws IOException {
        assertThat(ActivityStreamsValidator.strict().validate(json.read(example(name)))).isEmpty();
    }

    static Stream<Arguments> invalidDocuments() {
        return Stream.of(
            Arguments.of("{\"type\": \"Note\"}", "@context must include"),
            Arguments.of("{" + CONTEXT + "}", "type is required"),
            Arguments.of("{" + CONTEXT + ", \"type\": []}", "type must not be empty"),
            Arguments.of("{" + CONTEXT + ", \"type\": 3}", "type must be a string"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Tweet\"}",
                "type 'Tweet' is not an Activity Streams type"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Note\", \"id\": \"notes/1\"}",
                "id must be an absolute IRI"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Like\", \"actor\": 42}",
                "actor must be an IRI, an object or a list of them"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Like\", \"to\": [\"https://a/\", \"b\"]}",
                "to[1] must be an absolute IRI"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Note\", \"published\": \"2015-01-25\"}",
                "published must be an RFC 3339 date-time"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Note\","
                + " \"updated\": \"2015-01-25T12:00:00\"}", "updated must be an RFC 3339"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Create\", \"object\": {\"type\": \"Note\","
                + " \"published\": \"yesterday\"}}", "object.published must be an RFC 3339"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Note\", \"name\": [\"a\"]}",
                "name must be a string"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Note\", \"contentMap\": {\"en\": 1}}",
                "contentMap must be an object of language tags"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Collection\", \"totalItems\": -1}",
                "totalItems must be a non-negative integer"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Image\", \"width\": 1.5}",
                "width must be a non-negative integer"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Link\"}", "href is required for links"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Link\", \"href\": \"x\"}",
                "href must be an absolute IRI"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Link\", \"href\": \"https://a/\","
                + " \"mediaType\": \"pdf\"}", "mediaType must be a media type"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Link\", \"href\": \"https://a/\","
                + " \"hreflang\": \"not a tag\"}", "hreflang must be a BCP 47 language tag"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Link\", \"href\": \"https://a/\","
                + " \"rel\": \"a b\"}", "rel must hold link relations"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Video\", \"duration\": \"2 hours\"}",
                "duration must be an xsd:duration"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Place\", \"latitude\": 91}",
                "latitude must be a number between -90 and 90"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Place\", \"accuracy\": \"high\"}",
                "accuracy must be a number between 0 and 100"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Place\", \"units\": \"parsecs\"}",
                "units must be one of"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Arrive\", \"object\": \"https://a/\"}",
                "object must not be given for intransitive activities"),
            Arguments.of("{" + CONTEXT + ", \"type\": \"Question\", \"oneOf\": [], \"anyOf\": []}",
                "oneOf and anyOf must not both be given"),
            Arguments.of("{\"@context\": [\"https://www.w3.org/ns/activitystreams\", 1],"
                + " \"type\": \"Note\"}", "@context must hold IRIs or context objects"));
    }

    @ParameterizedTest
    @MethodSource("invalidDocuments")
    void reportsInvalidDocuments(final String document, final String expected) {
        assertThat(described(document)).anyMatch(violation -> violation.startsWith(expected));
    }

    @Test
    void reportsPathsOfNestedObjects() {
        final List<Violation> violations = strict("{" + CONTEXT + ", \"type\": \"Create\","
            + " \"object\": {\"type\": \"Note\", \"attachment\": [{\"type\": \"Link\"}]}}");
        assertThat(violations).containsExactly(
            new Violation("object.attachment[0].href", "is required for links"));
    }

    @Test
    void reportsEveryProblem() {
        assertThat(strict("{\"type\": \"Note\", \"id\": \"x\", \"published\": \"no\"}"))
            .extracting(Violation::path).containsExactly("@context", "id", "published");
    }

    @Test
    void impliesTheContextWhenLenient() {
        final AsObject note = json.read("{\"type\": \"Note\"}");
        assertThat(ActivityStreamsValidator.lenient().validate(note)).isEmpty();
        assertThat(ActivityStreamsValidator.strict().validate(note)).hasSize(1);
    }

    @Test
    void acceptsExtensionTypesAsIris() {
        assertThat(strict("{" + CONTEXT + ", \"type\": [\"Offer\", \"coar-notify:ReviewAction\","
            + " \"https://schema.org/Review\"]}")).isEmpty();
        assertThat(strict("{" + CONTEXT + ", \"type\": \"as:Note\"}")).isEmpty();
    }

    @Test
    void acceptsTermsOfExtensionContexts() {
        assertThat(strict("{\"@context\": [\"https://www.w3.org/ns/activitystreams\","
            + " \"https://w3id.org/security/v1\"], \"type\": \"Key\"}")).isEmpty();
    }

    @Test
    void acceptsClosedAsBooleanDateTimeOrObject() {
        for (final String closed : List.of("true", "\"2016-05-10T00:00:00Z\"",
                "\"https://a.example/answer\"", "{\"type\": \"Note\"}")) {
            assertThat(strict("{" + CONTEXT + ", \"type\": \"Question\", \"closed\": " + closed
                + "}")).isEmpty();
        }
        assertThat(described("{" + CONTEXT + ", \"type\": \"Question\", \"closed\": 7}"))
            .containsExactly("closed must be an IRI, an object or a list of them");
    }

    @Test
    void ignoresExtensionProperties() {
        assertThat(strict("{" + CONTEXT + ", \"type\": \"Note\", \"sensitive\": 1,"
            + " \"ietf:cite-as\": {\"weird\": true}}")).isEmpty();
    }

    @Test
    void stopsAtTheMaximumDepth() {
        Map<String, Object> object = Map.of("type", "Note");
        for (int depth = 0; depth < ActivityStreamsValidator.MAX_DEPTH + 5; depth++) {
            object = Map.of("type", "Note", "inReplyTo", object);
        }
        final AsObject deep = AsObject.builder().context(ActivityStreams.CONTEXT)
            .type(AsType.NOTE).property("inReplyTo", object).build();
        assertThat(ActivityStreamsValidator.strict().validate(deep)).singleElement()
            .satisfies(violation -> assertThat(violation.message()).startsWith("nests objects"));
    }

}
