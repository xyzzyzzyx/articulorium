package com.articulorum.platform.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;

import com.articulorum.platform.storage.BinaryStore;
import com.articulorum.platform.storage.StorageKeys;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Unit tests for {@link OcflHealthIndicator}.
 */
class OcflHealthIndicatorTest {

    private final BinaryStore store = mock(BinaryStore.class);

    /** The probe identifier is a valid object identifier the platform never mints for a resource. */
    @Test
    void probeIdentifierIsValid() {
        assertThat(StorageKeys.validate(OcflHealthIndicator.PROBE_OBJECT_ID)).startsWith(StorageKeys.OBJECT_ID_PREFIX);
    }

    /** Up when the storage root can be queried. */
    @Test
    void upWhenTheStorageAnswers() throws IOException {
        when(store.describe()).thenReturn("ocfl:s3:articulorum/ocfl");

        final Health health = new OcflHealthIndicator(store).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("storage", "ocfl:s3:articulorum/ocfl");
        verify(store).exists(OcflHealthIndicator.PROBE_OBJECT_ID);
    }

    /** Down when the storage root fails. */
    @Test
    void downWhenTheStorageFails() throws IOException {
        when(store.exists(OcflHealthIndicator.PROBE_OBJECT_ID)).thenThrow(new IOException("disk gone"));

        assertThat(new OcflHealthIndicator(store).health(true).getStatus()).isEqualTo(Status.DOWN);
    }

}
