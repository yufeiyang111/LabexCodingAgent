package com.labex.config;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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

/** 回归：t_ops_alert 旧指纹唯一键必须降级为普通索引，且仅在旧键存在时执行。 */
class AdditiveSchemaMigratorOpsAlertIndexTest {

    private static final List<String> BASE_INDEXES = List.of(
            "idx_token_task_epoch",
            "idx_task_retry_due",
            "idx_task_execution_lease",
            "idx_conv_project_updated",
            "idx_agent_conversation_fork_task",
            "idx_agent_conversation_execution_lease",
            "uk_agent_run_message_conversation_sequence",
            "idx_agent_run_message_conversation_parent",
            "idx_agent_run_part_message_sequence",
            "idx_msg_conversation_history",
            "idx_agent_compaction_conversation_scope");

    @Test
    void downgradesTheLegacyUniqueKeyWhenPresent() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AdditiveSchemaMigrator migrator = migrator(jdbcTemplate, true);

        migrator.migrate();

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).execute(sql.capture());
        org.junit.jupiter.api.Assertions.assertTrue(sql.getAllValues().contains(
                "ALTER TABLE t_ops_alert DROP INDEX uk_ops_alert_fingerprint_status"));
        org.junit.jupiter.api.Assertions.assertTrue(sql.getAllValues().contains(
                "ALTER TABLE t_ops_alert ADD INDEX idx_ops_alert_rule_fingerprint_status "
                        + "(rule_id, fingerprint, status)"));
    }

    @Test
    void isANoOpWhenTheLegacyUniqueKeyIsAlreadyGone() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AdditiveSchemaMigrator migrator = migrator(jdbcTemplate, false);

        migrator.migrate();

        verify(jdbcTemplate, never()).execute(anyString());
    }

    /** 全部列/表存在；索引存在性由给定名单决定。 */
    private AdditiveSchemaMigrator migrator(JdbcTemplate jdbcTemplate, boolean legacyUniquePresent)
            throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        DatabaseMetaData metadata = mock(DatabaseMetaData.class);
        ResultSet present = mock(ResultSet.class);
        java.util.List<String> indexNames = new java.util.ArrayList<>(BASE_INDEXES);
        if (legacyUniquePresent) {
            indexNames.add("uk_ops_alert_fingerprint_status");
        }
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getMetaData()).thenReturn(metadata);
        when(connection.getCatalog()).thenReturn("labex");
        when(present.next()).thenReturn(true);
        when(metadata.getColumns(any(), isNull(), anyString(), anyString())).thenReturn(present);
        when(metadata.getTables(any(), isNull(), anyString(), any())).thenReturn(present);
        when(metadata.getIndexInfo(eq("labex"), isNull(), anyString(), eq(false), eq(false)))
                .thenAnswer(invocation -> {
                    ResultSet indexes = mock(ResultSet.class);
                    when(indexes.next()).thenAnswer(new org.mockito.stubbing.Answer<Boolean>() {
                        private int pos = 0;
                        @Override
                        public Boolean answer(org.mockito.invocation.InvocationOnMock inv) {
                            return pos++ < indexNames.size();
                        }
                    });
                    when(indexes.getString("INDEX_NAME")).thenAnswer(new org.mockito.stubbing.Answer<String>() {
                        private int pos = 0;
                        @Override
                        public String answer(org.mockito.invocation.InvocationOnMock inv) {
                            if (pos < indexNames.size()) {
                                return indexNames.get(pos++);
                            }
                            return null;
                        }
                    });
                    return indexes;
                });
        return new AdditiveSchemaMigrator(jdbcTemplate, dataSource);
    }
}
