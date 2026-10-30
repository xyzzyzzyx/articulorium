package com.articulorum.platform.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;

import javax.sql.DataSource;

import com.articulorum.platform.repository.LdpResourceRepository;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

/**
 * Unit tests for {@link DatabaseHealthIndicator}, against an in-memory H2 database.
 */
class DatabaseHealthIndicatorTest {

    private final LdpResourceRepository repository = mock(LdpResourceRepository.class);

    private static DataSource h2() {
        final JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:health;DB_CLOSE_DELAY=-1");
        return dataSource;
    }

    /** Up when the database answers and the root container exists. */
    @Test
    void upWithRootContainer() {
        when(repository.count()).thenReturn(3L);
        when(repository.existsByPath("")).thenReturn(true);

        final Health health = new DatabaseHealthIndicator(h2(), repository).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("resources", 3L).containsEntry("rootContainer", true);
        assertThat((String) health.getDetails().get("database")).startsWith("H2");
    }

    /** Down when the repository has not been initialised. */
    @Test
    void downWithoutRootContainer() {
        when(repository.existsByPath("")).thenReturn(false);

        final Health health = new DatabaseHealthIndicator(h2(), repository).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("rootContainer", false).containsKey("error");
    }

    /** Down when no connection can be obtained. */
    @Test
    void downWhenTheDatabaseIsUnreachable() throws SQLException {
        final DataSource broken = mock(DataSource.class);
        when(broken.getConnection()).thenThrow(new SQLException("connection refused"));

        final Health health = new DatabaseHealthIndicator(broken, repository).health(true);

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
    }

}
