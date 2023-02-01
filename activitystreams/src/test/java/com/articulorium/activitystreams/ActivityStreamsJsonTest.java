package com.articulorium.activitystreams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link ActivityStreamsJson}.
 */
class ActivityStreamsJsonTest {

    private final ActivityStreamsJson json = new ActivityStreamsJson();

    private static byte[] example(final String name) throws IOException {
        try (InputStream in = ActivityStreamsJsonTest.class.getResourceAsStream("/examples/" + name)) {
            return in.readAllBytes();
        }
    }

    @Test
    void readsCommonProperties() throws IOException {
        final AsObject create = json.read(example("create-note.json"));

        assertThat(create.id()).contains("http://www.test.example/activity/1");
        assertThat(create.type()).contains(AsType.CREATE);
        assertThat(create.isActivity()).isTrue();
        assertThat(create.actor()).singleElement().satisfies(actor -> {
            assertThat(actor.id()).contains("http://www.test.example/sally");
            assertThat(actor.isA(AsType.PERSON)).isTrue();
            assertThat(actor.url()).containsExactly("http://example.org/sally");
        });
        assertThat(create.object()).singleElement().satisfies(note -> {
            assertThat(note.type()).contains(AsType.NOTE);
            assertThat(note.name()).contains("A Simple Note");
            assertThat(note.content()).contains("This is a simple note");
        });
        assertThat(create.published()).map(OffsetDateTime::toInstant)
            .contains(Instant.parse("2015-01-25T12:34:56Z"));
        assertThat(create.references("to")).containsExactly(ActivityStreams.PUBLIC,
            "http://www.test.example/sally/followers");
        assertThat(create.references("cc")).containsExactly("http://www.test.example/joe");
        assertThat(create.summary()).contains("Sally created a note");
    }

    @Test
    void readsLinksAndReferences() throws IOException {
        final AsObject video = json.read(example("link.json"));
        assertThat(video.url()).containsExactly("http://example.org/video.mkv",
            "http://example.org/video.mp4");
        assertThat(video.objects("url")).hasSize(2)
            .last().satisfies(link -> assertThat(link.isLink()).isTrue());
    }

    @ParameterizedTest
    @ValueSource(strings = {"create-note.json", "offer-review.json", "ordered-collection.json",
        "question.json", "place.json", "link.json", "extended-context.json"})
    void roundTripsDocuments(final String name) throws IOException {
        final AsObject read = json.read(example(name));
        final byte[] written = json.write(read);
        final AsObject reread = json.read(written);

        assertThat(reread).isEqualTo(read);
        assertThat(json.write(reread)).isEqualTo(written);
        // the same JSON as the original, whatever the formatting
        final JsonMapper mapper = JsonMapper.builder().build();
        assertThat(mapper.readTree(written)).isEqualTo(mapper.readTree(example(name)));
    }

    @Test
    void writesContextIdAndTypeFirstAndAddsTheContext() {
        final AsObject note = AsObject.of(Map.of("content", "Hi")).toBuilder()
            .type(AsType.NOTE).id("https://a.example/notes/1").build();

        assertThat(json.writeString(note)).isEqualTo("{\"@context\":\""
            + ActivityStreams.CONTEXT + "\",\"id\":\"https://a.example/notes/1\","
            + "\"type\":\"Note\",\"content\":\"Hi\"}");
    }

    @Test
    void producesActivitiesFromTheBuilder() {
        final AsObject like = AsObject.builder(AsType.LIKE)
            .id("urn:uuid:5e8d2b0c-0f0e-4e5b-9d7a-6a3b7f1c2d10")
            .actor(AsObject.builder(AsType.PERSON).id("https://a.example/alice").name("Alice")
                .build())
            .object("https://b.example/notes/1")
            .url(List.of("https://a.example/likes/1"))
            .published(Instant.parse("2026-11-05T17:32:00.123456Z"))
            .build();

        final String written = json.writeString(like);
        assertThat(written).contains("\"published\":\"2026-11-05T17:32:00.123Z\"")
            .contains("\"actor\":{\"type\":\"Person\",\"id\":\"https://a.example/alice\"");
        final AsObject read = json.readValid(written.getBytes(StandardCharsets.UTF_8),
            ActivityStreams.JSON_LD_MEDIA_TYPE);
        assertThat(read.object()).extracting(object -> object.id().orElseThrow())
            .containsExactly("https://b.example/notes/1");
        assertThat(ActivityStreamsValidator.strict().validate(read)).isEmpty();
    }

    @Test
    void rejectsMalformedJson() {
        for (final String malformed : List.of("", "{", "{\"type\": }", "nope",
                "{\"type\": \"Note\"} {}")) {
            assertThatThrownBy(() -> json.read(malformed)).isInstanceOf(ActivityStreamsException.class)
                .hasMessageStartingWith("is not valid JSON");
        }
    }

    @Test
    void rejectsJsonThatIsNotAnObject() {
        for (final String notAnObject : List.of("[]", "\"Note\"", "42", "null")) {
            assertThatThrownBy(() -> json.read(notAnObject))
                .isInstanceOf(ActivityStreamsException.class)
                .hasMessage("must be a JSON object");
        }
    }

    @Test
    void rejectsDuplicateProperties() {
        assertThatThrownBy(() -> json.read("{\"type\": \"Note\", \"type\": \"Like\"}"))
            .isInstanceOf(ActivityStreamsException.class)
            .hasMessageContaining("Duplicate");
    }

    @Test
    void readValidImpliesTheContextForActivityJson() {
        final byte[] note = "{\"type\": \"Note\"}".getBytes(StandardCharsets.UTF_8);

        final AsObject read = json.readValid(note, "application/activity+json; charset=utf-8");
        assertThat(read.context()).containsExactly(ActivityStreams.CONTEXT);

        assertThatThrownBy(() -> json.readValid(note, ActivityStreams.JSON_LD_MEDIA_TYPE))
            .isInstanceOfSatisfying(ActivityStreamsException.class, error ->
                assertThat(error.getViolations()).extracting(Violation::path)
                    .containsExactly("@context"));
    }

    @Test
    void keepsTheGivenMapperUnchanged() {
        final JsonMapper mapper = JsonMapper.builder().build();
        new ActivityStreamsJson(mapper).read("{\"type\": \"Note\"}");
        assertThat(mapper.readValue("{\"a\": 1, \"a\": 2}", Map.class)).containsEntry("a", 2);
    }

}
