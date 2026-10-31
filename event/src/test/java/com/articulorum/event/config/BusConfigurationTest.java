package com.articulorum.event.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.articulorum.event.EventDestinations;
import com.articulorum.event.ResourceEvent;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.MapperFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Unit tests for {@link BusConfiguration}.
 */
class BusConfigurationTest {

    private final BusConfiguration configuration = new BusConfiguration();

    /**
     * The customizer must turn on final-field mutation, otherwise Jackson 3 drops the origin and
     * id of every received bus event.
     */
    @Test
    void customizerEnablesFinalFieldMutation() {
        final JsonMapper.Builder builder = JsonMapper.builder();

        configuration.busEventFinalFieldsCustomizer().customize(builder);

        assertThat(builder.build().isEnabled(MapperFeature.ALLOW_FINAL_FIELDS_AS_MUTATORS)).isTrue();
    }

    /**
     * End-to-end check with a mapper built through the customizer: origin and id survive.
     *
     * @throws Exception when (de)serialisation fails
     */
    @Test
    void customizedMapperKeepsEventOriginAndId() throws Exception {
        final JsonMapper.Builder builder = JsonMapper.builder().registerSubtypes(ResourceEvent.class);
        configuration.busEventFinalFieldsCustomizer().customize(builder);
        final JsonMapper mapper = builder.build();
        final ResourceEvent event = new ResourceEvent(this, "platform:7", EventDestinations.all(), null, null,
            "http://localhost:9000", null);

        final ResourceEvent copy = mapper.readValue(mapper.writeValueAsString(event), ResourceEvent.class);

        assertThat(copy.getOriginService()).isEqualTo("platform:7");
        assertThat(copy.getId()).isEqualTo(event.getId());
    }

}
