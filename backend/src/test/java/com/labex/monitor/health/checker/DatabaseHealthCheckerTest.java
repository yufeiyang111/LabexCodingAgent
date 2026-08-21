package com.labex.monitor.health;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.monitor.health.checker.DatabaseHealthChecker;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.UncategorizedSQLException;

class DatabaseHealthCheckerTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final DatabaseHealthChecker checker = new DatabaseHealthChecker(jdbc);

    @Test
    void selectOneReturnsUp() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
        assertThat(result.name()).isEqualTo("mysql");
        assertThat(result.latencyMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void databaseUnavailableIsDownWithoutLeakingConnectionString() {
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new UncategorizedSQLException("jdbc:mysql://user:supersecret@internal:3306/db",
                        "SELECT 1", new java.sql.SQLException("Communications link failure")));

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.DOWN);
        assertThat(result.safeMessage()).doesNotContain("supersecret");
        assertThat(result.safeMessage()).doesNotContain("jdbc:mysql");
        assertThat(result.safeMessage()).doesNotContain("Communications link failure");
        assertThat(result.errorCode()).isNotBlank();
    }

    @Test
    void queryReturningUnexpectedValueIsStillUp() {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(7);

        HealthCheckResult result = checker.check();

        assertThat(result.status()).isEqualTo(HealthStatus.UP);
    }
}
