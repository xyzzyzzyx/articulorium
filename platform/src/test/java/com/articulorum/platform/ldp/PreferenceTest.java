package com.articulorum.platform.ldp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.articulorum.domain.vocabulary.Ldp;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Preference}.
 */
class PreferenceTest {

    @Test
    void defaultsIncludeEverything() {
        assertEquals(Preference.DEFAULT, Preference.parse(null));
        assertEquals(Preference.DEFAULT, Preference.parse(List.of()));
        assertTrue(Preference.DEFAULT.includeContainment());
        assertTrue(Preference.DEFAULT.includeMembership());
        assertFalse(Preference.DEFAULT.applied());
    }

    @Test
    void minimalContainerOmitsContainmentAndMembership() {
        final Preference preference = Preference.parse(List.of(
            "return=representation; include=\"" + Ldp.PREFER_MINIMAL_CONTAINER + "\""));
        assertFalse(preference.includeContainment());
        assertFalse(preference.includeMembership());
        assertTrue(preference.applied());
    }

    @Test
    void legacyEmptyContainerTokenIsUnderstood() {
        final Preference preference = Preference.parse(List.of(
            "return=representation; include=\"" + Ldp.PREFER_EMPTY_CONTAINER + "\""));
        assertFalse(preference.includeContainment());
    }

    @Test
    void minimalContainerCanReincludeContainment() {
        final Preference preference = Preference.parse(List.of("return=representation; include=\""
            + Ldp.PREFER_MINIMAL_CONTAINER + " " + Ldp.PREFER_CONTAINMENT + "\""));
        assertTrue(preference.includeContainment());
        assertFalse(preference.includeMembership());
    }

    @Test
    void omitRemovesSelectedTriples() {
        final Preference preference = Preference.parse(List.of(
            "return=representation; omit=\"" + Ldp.PREFER_MEMBERSHIP + "\""));
        assertTrue(preference.includeContainment());
        assertFalse(preference.includeMembership());
        assertTrue(preference.applied());
    }

    @Test
    void omitMinimalContainerMeansFullRepresentation() {
        final Preference preference = Preference.parse(List.of(
            "return=representation; omit=\"" + Ldp.PREFER_MINIMAL_CONTAINER + "\""));
        assertTrue(preference.includeContainment());
        assertTrue(preference.includeMembership());
    }

    @Test
    void otherPreferencesAreIgnored() {
        assertEquals(Preference.DEFAULT, Preference.parse(List.of("respond-async, wait=10")));
        assertEquals(Preference.DEFAULT, Preference.parse(List.of("return=minimal")));
        assertEquals(Preference.DEFAULT, Preference.parse(List.of("return=representation")));
    }

    @Test
    void preferenceMayFollowAnotherInTheSameHeader() {
        final Preference preference = Preference.parse(List.of(
            "respond-async, return=representation; omit=\"" + Ldp.PREFER_CONTAINMENT + "\""));
        assertFalse(preference.includeContainment());
    }

}
