package com.articulorum.platform.ldp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link Slugs}.
 */
class SlugsTest {

    @Test
    void keepsUnreservedCharacters() {
        assertEquals(Optional.of("Report_2024-v1.0~final"), Slugs.sanitize("Report_2024-v1.0~final"));
    }

    @Test
    void replacesUnsafeCharactersWithSingleDashes() {
        assertEquals(Optional.of("my-file-name.pdf"), Slugs.sanitize("  my file / name.pdf "));
        assertEquals(Optional.of("a-b"), Slugs.sanitize("a?#&b"));
    }

    @Test
    void decodesPercentEncoding() {
        assertEquals(Optional.of("hello-world"), Slugs.sanitize("hello%20world"));
    }

    @Test
    void preservesPlusSigns() {
        assertEquals(Optional.of("a-b"), Slugs.sanitize("a+b"));
    }

    @Test
    void rejectsEmptyAndDotSegments() {
        assertEquals(Optional.empty(), Slugs.sanitize(null));
        assertEquals(Optional.empty(), Slugs.sanitize("   "));
        assertEquals(Optional.empty(), Slugs.sanitize("///"));
        assertEquals(Optional.empty(), Slugs.sanitize("."));
        assertEquals(Optional.empty(), Slugs.sanitize(".."));
    }

    @Test
    void toleratesInvalidPercentEncoding() {
        assertEquals(Optional.of("100"), Slugs.sanitize("100%"));
    }

    @Test
    void truncatesLongSlugs() {
        final String result = Slugs.sanitize("x".repeat(500)).orElseThrow();
        assertTrue(result.length() <= Slugs.MAX_LENGTH);
    }

}
