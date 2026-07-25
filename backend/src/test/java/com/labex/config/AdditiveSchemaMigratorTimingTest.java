package com.labex.config;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class AdditiveSchemaMigratorTimingTest {

    @Test
    void addsEveryMissingAgentTaskTimingColumn() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet missing = mock(ResultSet.class);
        ResultSet present = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("labex");
        when(missing.next()).thenReturn(false);
        when(present.next()).thenReturn(true);
        when(metadata.getColumns(any(), isNull(), anyString(), anyString())).thenAnswer(invocation -> {
            String column = invocation.getArgument(3, String.class);
            return List.of(
                    "submitted_at",
                    "started_at",
                    "active_segment_started_at",
                    "finished_at",
                    "elapsed_ms",
                    "active_elapsed_ms").contains(column) ? missing : present;
        });
        when(metadata.getTables(any(), isNull(), anyString(), any())).thenReturn(present);

        new AdditiveSchemaMigrator(jdbcTemplate, dataSource).migrate();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(10)).execute(sql.capture());
        assertTrue(sql.getAllValues().containsAll(List.of(
                "ALTER TABLE t_agent_task ADD COLUMN submitted_at DATETIME(3) DEFAULT NULL",
                "ALTER TABLE t_agent_task ADD COLUMN started_at DATETIME(3) DEFAULT NULL",
                "ALTER TABLE t_agent_task ADD COLUMN active_segment_started_at DATETIME(3) DEFAULT NULL",
                "ALTER TABLE t_agent_task ADD COLUMN finished_at DATETIME(3) DEFAULT NULL",
                "ALTER TABLE t_agent_task ADD COLUMN elapsed_ms BIGINT DEFAULT NULL",
                "ALTER TABLE t_agent_task ADD COLUMN active_elapsed_ms BIGINT NOT NULL DEFAULT 0",
                "ALTER TABLE t_agent_task ADD INDEX idx_task_retry_due (status, next_retry_at)",
                "ALTER TABLE t_agent_task ADD INDEX idx_task_execution_lease (execution_lease_expires_at)",
                "ALTER TABLE t_agent_conversation ADD INDEX idx_conv_project_updated (student_id, project_id, status, update_time)",
                "ALTER TABLE t_agent_message ADD INDEX idx_msg_conversation_history (conversation_id, student_id, project_id, message_id)")));
    }

    @Test
    void createsCommandAuditTableForAnExistingDatabaseMissingTheNewTable() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet present = mock(ResultSet.class);
        ResultSet missing = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("labex");
        when(present.next()).thenReturn(true);
        when(missing.next()).thenReturn(false);
        when(metadata.getColumns(any(), isNull(), anyString(), anyString())).thenReturn(present);
        when(metadata.getTables(any(), isNull(), anyString(), any())).thenReturn(missing);

        new AdditiveSchemaMigrator(jdbcTemplate, dataSource).migrate();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(5)).execute(sql.capture());
        assertTrue(sql.getAllValues().stream().anyMatch(statement -> statement.contains("CREATE TABLE t_command_audit_event")));
        assertTrue(sql.getAllValues().stream().anyMatch(statement -> statement.contains("uk_command_audit_approval_idempotency")));
    }

    @Test
    void doesNotCreateRunIndexesWhenTheyAlreadyExist() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet present = mock(ResultSet.class);
        ResultSet existingIndexes = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("labex");
        when(present.next()).thenReturn(true);
        when(metadata.getColumns(any(), isNull(), anyString(), anyString())).thenReturn(present);
        when(metadata.getTables(any(), isNull(), anyString(), any())).thenReturn(present);
        when(existingIndexes.next()).thenReturn(true, true, true, true, false);
        when(existingIndexes.getString("INDEX_NAME")).thenReturn(
                "idx_task_retry_due",
                "idx_task_execution_lease",
                "idx_conv_project_updated",
                "idx_msg_conversation_history");
        when(metadata.getIndexInfo(eq("labex"), isNull(), anyString(), eq(false), eq(false)))
                .thenReturn(existingIndexes);

        new AdditiveSchemaMigrator(jdbcTemplate, dataSource).migrate();

        verify(jdbcTemplate, org.mockito.Mockito.never()).execute(anyString());
    }
}
