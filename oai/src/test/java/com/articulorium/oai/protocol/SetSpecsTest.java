package com.articulorium.oai.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link SetSpecs}.
 */
class SetSpecsTest {

    @Test
    void mapsPathSegmentsToSetSpecComponents() {
        assertThat(SetSpecs.encode("arts")).isEqualTo("arts");
        assertThat(SetSpecs.encode("arts/paintings")).isEqualTo("arts:paintings");
        assertThat(SetSpecs.decode("arts:paintings")).isEqualTo("arts/paintings");
    }

    @ParameterizedTest
    @ValueSource(strings = {"a-b_c.d~e", "with space/and%20escapes", "colon:in/segment", "bang!/café",
        "star*/quote'(x)"})
    void roundTripsAnyPathIntoALegalSetSpec(final String path) {
        final String spec = SetSpecs.encode(path);
        assertThat(spec).matches("[A-Za-z0-9\\-_.!~*'()]+(:[A-Za-z0-9\\-_.!~*'()]+)*");
        assertThat(SetSpecs.decode(spec)).isEqualTo(path);
    }

    @Test
    void rejectsIllegalAndForeignSetSpecs() {
        assertThatThrownBy(() -> SetSpecs.decode("a b")).isInstanceOfSatisfying(OaiException.class,
            e -> assertThat(e.getError()).isEqualTo(OaiError.BAD_ARGUMENT));
        assertThatThrownBy(() -> SetSpecs.decode("a::b")).isInstanceOfSatisfying(OaiException.class,
            e -> assertThat(e.getError()).isEqualTo(OaiError.BAD_ARGUMENT));
        // legal, but never minted here
        for (final String spec : new String[] {"a*", "!", "!4", "!ZZ", "a!2F"}) {
            assertThatThrownBy(() -> SetSpecs.decode(spec)).isInstanceOfSatisfying(OaiException.class,
                e -> assertThat(e.getError()).isEqualTo(OaiError.NO_RECORDS_MATCH));
        }
    }

}
