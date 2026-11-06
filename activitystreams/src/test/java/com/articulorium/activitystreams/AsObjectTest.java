package com.articulorium.activitystreams;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AsObject}.
 */
class AsObjectTest {

    @Test
    void copiesPropertiesDeeply() {
        final List<Object> to = new ArrayList<>(List.of("https://a.example/"));
        final Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("type", "Note");
        properties.put("to", to);
        final AsObject note = AsObject.of(properties);

        to.add("https://b.example/");
        properties.put("name", "changed");

        assertThat(note.references("to")).containsExactly("https://a.example/");
        assertThat(note.has("name")).isFalse();
        assertThatThrownBy(() -> note.properties().put("x", 1))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void convertsValuesToJson() {
        final AsObject object = AsObject.builder(AsType.EVENT)
            .property("startTime", OffsetDateTime.parse("2026-11-05T17:32:00-06:00"))
            .property("url", URI.create("https://a.example/event"))
            .property("formerType", AsType.PLACE)
            .property("attributedTo", AsObject.reference("https://a.example/bob"))
            .build();

        assertThat(object.properties()).containsEntry("startTime", "2026-11-05T17:32-06:00")
            .containsEntry("url", "https://a.example/event")
            .containsEntry("formerType", "Place")
            .containsEntry("attributedTo", Map.of("id", "https://a.example/bob"));
        assertThat(object.dateTime("startTime")).contains(
            OffsetDateTime.parse("2026-11-05T17:32:00-06:00"));
        assertThatThrownBy(() -> AsObject.builder().property("x", new Object()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsSingleValuesAndListsAlike() {
        final AsObject one = AsObject.builder(AsType.LIKE).actor("https://a.example/").build();
        final AsObject many = AsObject.builder(AsType.LIKE)
            .actor(List.of("https://a.example/", AsObject.reference("https://b.example/"))).build();

        assertThat(one.actor()).extracting(actor -> actor.id().orElseThrow())
            .containsExactly("https://a.example/");
        assertThat(many.references("actor")).containsExactly("https://a.example/",
            "https://b.example/");
        assertThat(many.types()).containsExactly("Like");
    }

    @Test
    void classifiesTypes() {
        final AsObject mixed = AsObject.builder().type("coar-notify:ReviewAction", AsType.ANNOUNCE)
            .build();
        assertThat(mixed.types()).containsExactly("coar-notify:ReviewAction", "Announce");
        assertThat(mixed.type()).contains(AsType.ANNOUNCE);
        assertThat(mixed.isActivity()).isTrue();
        assertThat(mixed.isA(AsType.ANNOUNCE)).isTrue();
        assertThat(mixed.isA(AsType.OFFER)).isFalse();
        assertThat(AsObject.reference("https://a/").type()).isEmpty();
    }

    @Test
    void ignoresValuesOfTheWrongShape() {
        final AsObject odd = AsObject.of(Map.of("name", 7, "published", "soon", "actor", 3));
        assertThat(odd.name()).isEmpty();
        assertThat(odd.published()).isEmpty();
        assertThat(odd.actor()).isEmpty();
        assertThat(odd.get("name")).contains(7);
    }

    @Test
    void buildsModifiedCopies() {
        final AsObject note = AsObject.builder(AsType.NOTE).name("a").summary("s").build();
        final AsObject renamed = note.toBuilder().name("b").summary(null).build();

        assertThat(note.name()).contains("a");
        assertThat(renamed.name()).contains("b");
        assertThat(renamed.has("summary")).isFalse();
        assertThat(renamed).isNotEqualTo(note);
        assertThat(note.toBuilder().build()).isEqualTo(note).hasSameHashCodeAs(note);
    }

}
