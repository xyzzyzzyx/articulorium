package com.articulorium.activitystreams;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ActivityStreams}.
 */
class ActivityStreamsTest {

    @Test
    void recognisesMediaTypes() {
        assertThat(ActivityStreams.isActivityJson("application/activity+json")).isTrue();
        assertThat(ActivityStreams.isActivityJson("Application/Activity+JSON; charset=utf-8"))
            .isTrue();
        assertThat(ActivityStreams.isActivityJson(ActivityStreams.JSON_LD_PROFILE_MEDIA_TYPE))
            .isFalse();
        assertThat(ActivityStreams.isActivityJson(null)).isFalse();

        assertThat(ActivityStreams.isSupportedMediaType(ActivityStreams.JSON_LD_PROFILE_MEDIA_TYPE))
            .isTrue();
        assertThat(ActivityStreams.isSupportedMediaType("application/json")).isTrue();
        assertThat(ActivityStreams.isSupportedMediaType("text/turtle")).isFalse();
        assertThat(ActivityStreams.isSupportedMediaType(null)).isFalse();
    }

    @Test
    void namesThePublicCollection() {
        assertThat(ActivityStreams.PUBLIC).isEqualTo("https://www.w3.org/ns/activitystreams#Public");
    }

}
