package com.articulorium.oai.protocol;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/**
 * Tests {@link OaiDates} (OAI-PMH 2.0 section 3.3).
 */
class OaiDatesTest {

    @Test
    void parsesDaysAndSecondsWithTheirGranules() {
        final OaiDates.Datestamp day = OaiDates.parse("from", "2026-11-05");
        assertThat(day.start()).isEqualTo(Instant.parse("2026-11-05T00:00:00Z"));
        assertThat(day.end()).isEqualTo(Instant.parse("2026-11-06T00:00:00Z"));
        final OaiDates.Datestamp second = OaiDates.parse("until", "2026-11-05T18:30:15Z");
        assertThat(second.granularity()).isEqualTo(OaiDates.Granularity.SECOND);
        assertThat(second.end()).isEqualTo(Instant.parse("2026-11-05T18:30:16Z"));
    }

    @Test
    void rejectsOtherFormsAndImpossibleDates() {
        for (final String value : new String[] {"2026-11-05T18:30Z", "2026-11-05T18:30:15.5Z",
            "2026-11-05T18:30:15+01:00", "2026-02-30", "05/11/2026", "2026"}) {
            assertThatThrownBy(() -> OaiDates.parse("from", value))
                .isInstanceOfSatisfying(OaiException.class,
                    e -> assertThat(e.getError()).isEqualTo(OaiError.BAD_ARGUMENT));
        }
    }

    @Test
    void checksRanges() {
        OaiDates.checkRange(OaiDates.parse("from", "2026-01-01"), OaiDates.parse("until", "2026-01-01"));
        OaiDates.checkRange(null, OaiDates.parse("until", "2026-01-01"));
        assertThatThrownBy(() -> OaiDates.checkRange(OaiDates.parse("from", "2026-01-02"),
            OaiDates.parse("until", "2026-01-01"))).isInstanceOf(OaiException.class);
        assertThatThrownBy(() -> OaiDates.checkRange(OaiDates.parse("from", "2026-01-01"),
            OaiDates.parse("until", "2026-01-02T00:00:00Z"))).hasMessageContaining("granularities");
    }

    @Test
    void formatsAtSecondsTruncatingFractions() {
        assertThat(OaiDates.format(Instant.parse("2026-11-05T18:30:15.987654Z"))).isEqualTo("2026-11-05T18:30:15Z");
        assertThat(OaiDates.format(Instant.parse("2026-11-05T18:30:00Z"))).isEqualTo("2026-11-05T18:30:00Z");
    }

}
