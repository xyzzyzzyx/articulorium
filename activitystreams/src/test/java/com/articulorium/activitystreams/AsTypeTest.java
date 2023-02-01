package com.articulorium.activitystreams;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link AsType}.
 */
class AsTypeTest {

    @Test
    void coversTheActivityVocabulary() {
        assertThat(AsType.values()).hasSize(54);
        assertThat(Arrays.stream(AsType.values()).filter(AsType::isActivity)).hasSize(30);
        assertThat(Arrays.stream(AsType.values()).filter(type -> type.kind() == AsType.Kind.ACTOR))
            .containsExactly(AsType.APPLICATION, AsType.GROUP, AsType.ORGANIZATION, AsType.PERSON,
                AsType.SERVICE);
    }

    @Test
    void looksTypesUpByTermAndIri() {
        assertThat(AsType.of("Note")).contains(AsType.NOTE);
        assertThat(AsType.of("as:TentativeAccept")).contains(AsType.TENTATIVE_ACCEPT);
        assertThat(AsType.of("https://www.w3.org/ns/activitystreams#OrderedCollectionPage"))
            .contains(AsType.ORDERED_COLLECTION_PAGE);
        assertThat(AsType.of("note")).isEmpty();
        assertThat(AsType.of("coar-notify:ReviewAction")).isEmpty();
        assertThat(AsType.of(null)).isEmpty();
    }

    @Test
    void namesTermsAndIris() {
        assertThat(AsType.INTRANSITIVE_ACTIVITY.term()).isEqualTo("IntransitiveActivity");
        assertThat(AsType.MENTION.iri()).isEqualTo("https://www.w3.org/ns/activitystreams#Mention");
        assertThat(AsType.QUESTION.kind()).isEqualTo(AsType.Kind.INTRANSITIVE_ACTIVITY);
        assertThat(AsType.QUESTION.isActivity()).isTrue();
        assertThat(AsType.NOTE.isActivity()).isFalse();
    }

}
