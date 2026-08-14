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
        verify(jdbcTemplate, org.mockito.Mockito.times(12)).execute(sql.capture());
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
                "ALTER TABLE t_agent_conversation ADD INDEX idx_agent_conversation_fork_task (forked_from_task_id)",
                "ALTER TABLE t_agent_message ADD INDEX idx_msg_conversation_history (conversation_id, student_id, project_id, message_id)",
                "ALTER TABLE t_agent_compaction_record ADD INDEX idx_agent_compaction_conversation_scope "
                        + "(conversation_id, student_id, project_id, scope, status, compaction_epoch)")));
    }

    @Test
    void addsDurableCommandProcessIdentityColumnsToAnExistingAuditTable() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet present = mock(ResultSet.class);
        ResultSet missing = mock(ResultSet.class);
        ResultSet existingIndexes = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("labex");
        when(present.next()).thenReturn(true);
        when(missing.next()).thenReturn(false);
        when(metadata.getTables(any(), isNull(), anyString(), any())).thenReturn(present);
        when(metadata.getColumns(any(), isNull(), anyString(), anyString())).thenAnswer(invocation -> {
            String table = invocation.getArgument(2, String.class);
            String column = invocation.getArgument(3, String.class);
            boolean missingProcessColumn = "t_command_audit_event".equalsIgnoreCase(table)
                    && List.of("process_host_id", "process_owner", "worker_runtime", "worker_run_id",
                    "process_id", "process_start_epoch_ms", "process_lease_expires_epoch_ms")
                    .contains(column.toLowerCase());
            return missingProcessColumn ? missing : present;
        });
        when(existingIndexes.next()).thenReturn(true, true, true, true, true, true, false);
        when(existingIndexes.getString("INDEX_NAME")).thenReturn(
                "idx_task_retry_due",
                "idx_task_execution_lease",
                "idx_conv_project_updated",
                "idx_agent_conversation_fork_task",
                "idx_msg_conversation_history",
                "idx_agent_compaction_conversation_scope");
        when(metadata.getIndexInfo(eq("labex"), isNull(), anyString(), eq(false), eq(false)))
                .thenReturn(existingIndexes);

        new AdditiveSchemaMigrator(jdbcTemplate, dataSource).migrate();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(7)).execute(sql.capture());
        assertTrue(sql.getAllValues().containsAll(List.of(
                "ALTER TABLE t_command_audit_event ADD COLUMN process_host_id VARCHAR(64) DEFAULT NULL",
                "ALTER TABLE t_command_audit_event ADD COLUMN process_owner VARCHAR(128) DEFAULT NULL",
                "ALTER TABLE t_command_audit_event ADD COLUMN worker_runtime VARCHAR(32) DEFAULT NULL",
                "ALTER TABLE t_command_audit_event ADD COLUMN worker_run_id VARCHAR(128) DEFAULT NULL",
                "ALTER TABLE t_command_audit_event ADD COLUMN process_id BIGINT DEFAULT NULL",
                "ALTER TABLE t_command_audit_event ADD COLUMN process_start_epoch_ms BIGINT DEFAULT NULL",
                "ALTER TABLE t_command_audit_event ADD COLUMN process_lease_expires_epoch_ms BIGINT DEFAULT NULL")));
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
        // 新增表迁移只会增加 execute 次数（当前包含 t_agent_input_attachment 等），用 atLeast 保持语义不脆断。
        verify(jdbcTemplate, org.mockito.Mockito.atLeast(8)).execute(sql.capture());
        assertTrue(sql.getAllValues().stream().anyMatch(statement -> statement.contains("CREATE TABLE t_command_audit_event")));
        assertTrue(sql.getAllValues().stream().anyMatch(statement -> statement.contains("uk_command_audit_approval_idempotency")));
        assertTrue(sql.getAllValues().stream().anyMatch(statement -> statement.contains("CREATE TABLE t_agent_project_checkout_lease")));
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
        when(existingIndexes.next()).thenReturn(true, true, true, true, true, true, false);
        when(existingIndexes.getString("INDEX_NAME")).thenReturn(
                "idx_task_retry_due",
                "idx_task_execution_lease",
                "idx_conv_project_updated",
                "idx_agent_conversation_fork_task",
                "idx_msg_conversation_history",
                "idx_agent_compaction_conversation_scope");
        when(metadata.getIndexInfo(eq("labex"), isNull(), anyString(), eq(false), eq(false)))
                .thenReturn(existingIndexes);

        new AdditiveSchemaMigrator(jdbcTemplate, dataSource).migrate();

        verify(jdbcTemplate, org.mockito.Mockito.never()).execute(anyString());
    }

    @Test
    void addsConversationCompactionScopeBoundaryAndLookupIndex() throws Exception {
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
        when(metadata.getTables(any(), isNull(), anyString(), any())).thenReturn(present);
        when(metadata.getColumns(any(), isNull(), anyString(), anyString())).thenAnswer(invocation -> {
            String table = invocation.getArgument(2, String.class);
            String column = invocation.getArgument(3, String.class);
            return "t_agent_compaction_record".equalsIgnoreCase(table)
                    && List.of("scope", "source_max_task_id").contains(column.toLowerCase())
                    ? missing : present;
        });

        new AdditiveSchemaMigrator(jdbcTemplate, dataSource).migrate();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeast(3)).execute(sql.capture());
        assertTrue(sql.getAllValues().contains(
                "ALTER TABLE t_agent_compaction_record ADD COLUMN scope VARCHAR(24) NOT NULL DEFAULT 'task'"));
        assertTrue(sql.getAllValues().contains(
                "ALTER TABLE t_agent_compaction_record ADD COLUMN source_max_task_id BIGINT DEFAULT NULL"));
        assertTrue(sql.getAllValues().contains(
                "ALTER TABLE t_agent_compaction_record ADD INDEX idx_agent_compaction_conversation_scope "
                        + "(conversation_id, student_id, project_id, scope, status, compaction_epoch)"));
    }

    @Test
    void addsCacheTelemetryTokenColumnsToExistingUsageTable() throws Exception {
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
        when(metadata.getTables(any(), isNull(), anyString(), any())).thenReturn(present);
        when(metadata.getColumns(any(), isNull(), anyString(), anyString())).thenAnswer(invocation -> {
            String table = invocation.getArgument(2, String.class);
            String column = invocation.getArgument(3, String.class);
            return "t_agent_token_usage".equalsIgnoreCase(table)
                    && List.of("cache_hit_tokens", "cache_miss_tokens").contains(column.toLowerCase())
                    ? missing : present;
        });

        new AdditiveSchemaMigrator(jdbcTemplate, dataSource).migrate();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeast(2)).execute(sql.capture());
        assertTrue(sql.getAllValues().contains(
                "ALTER TABLE t_agent_token_usage ADD COLUMN cache_hit_tokens INT NOT NULL DEFAULT 0"));
        assertTrue(sql.getAllValues().contains(
                "ALTER TABLE t_agent_token_usage ADD COLUMN cache_miss_tokens INT NOT NULL DEFAULT 0"));
    }

    @Test
    void addsDurableConversationProjectionColumnsAndForkIndex() throws Exception {
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
        when(metadata.getTables(any(), isNull(), anyString(), any())).thenReturn(present);
        when(metadata.getColumns(any(), isNull(), anyString(), anyString())).thenAnswer(invocation -> {
            String table = invocation.getArgument(2, String.class);
            String column = invocation.getArgument(3, String.class);
            return "t_agent_conversation".equalsIgnoreCase(table)
                    && List.of("forked_from_task_id", "history_projection_version", "history_migrated_at")
                    .contains(column.toLowerCase()) ? missing : present;
        });

        new AdditiveSchemaMigrator(jdbcTemplate, dataSource).migrate();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.atLeast(4)).execute(sql.capture());
        assertTrue(sql.getAllValues().contains(
                "ALTER TABLE t_agent_conversation ADD COLUMN forked_from_task_id BIGINT DEFAULT NULL"));
        assertTrue(sql.getAllValues().contains(
                "ALTER TABLE t_agent_conversation ADD COLUMN history_projection_version VARCHAR(32) DEFAULT NULL"));
        assertTrue(sql.getAllValues().contains(
                "ALTER TABLE t_agent_conversation ADD COLUMN history_migrated_at DATETIME(3) DEFAULT NULL"));
        assertTrue(sql.getAllValues().contains(
                "ALTER TABLE t_agent_conversation ADD INDEX idx_agent_conversation_fork_task (forked_from_task_id)"));
    }

}
