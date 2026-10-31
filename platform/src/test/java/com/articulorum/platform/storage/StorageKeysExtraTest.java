package com.articulorum.platform.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * Tests the archival group layout helpers of {@link StorageKeys}.
 */
class StorageKeysExtraTest {

    @Test
    void ownersKeepTheirFilesAtTheTopLevel() {
        assertThat(StorageKeys.resourceDirectory("")).isEmpty();
        assertThat(StorageKeys.descriptionPath("")).isEqualTo(StorageKeys.DESCRIPTION);
        assertThat(StorageKeys.contentPath("")).isEqualTo(StorageKeys.CONTENT);
        assertThat(StorageKeys.technicalPath("")).isEqualTo(StorageKeys.TECHNICAL);
    }

    @Test
    void membersLiveInNestedMemberDirectories() {
        assertThat(StorageKeys.resourceDirectory("a/b")).isEqualTo("members/a/members/b/");
        assertThat(StorageKeys.contentPath("pages/content")).isEqualTo("members/pages/members/content/content");
        assertThat(StorageKeys.descriptionPath("description.nt"))
            .isEqualTo("members/description.nt/description.nt");
    }

    @Test
    void computesRelativePaths() {
        assertThat(StorageKeys.relativePath("books/moby", "books/moby")).isEmpty();
        assertThat(StorageKeys.relativePath("books/moby", "books/moby/p1/img")).isEqualTo("p1/img");
        assertThat(StorageKeys.relativePath("", "books")).isEqualTo("books");
        assertThatThrownBy(() -> StorageKeys.relativePath("books/moby", "books/other"))
            .isInstanceOf(IllegalArgumentException.class);
    }

}
