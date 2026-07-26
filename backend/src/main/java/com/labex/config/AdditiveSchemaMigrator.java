package com.labex.config;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@DependsOn("dataSourceScriptDatabaseInitializer")
public class AdditiveSchemaMigrator {
    private static final Logger log = LoggerFactory.getLogger(AdditiveSchemaMigrator.class);
    private static final List<ColumnDefinition> REQUIRED_COLUMNS = List.of(
            new ColumnDefinition("t_agent_model_config", "context_window_tokens", "INT"),
            new ColumnDefinition("t_agent_model_config", "prompt_cache_key_enabled", "TINYINT NOT NULL DEFAULT 0"),
            new ColumnDefinition("t_agent_model_config", "reasoning_effort", "VARCHAR(16) NOT NULL DEFAULT 'medium'"),
            new ColumnDefinition("t_agent_model_config", "image_input_enabled", "TINYINT NOT NULL DEFAULT 0"),
            new ColumnDefinition("t_agent_model_config", "compaction_auto", "TINYINT NOT NULL DEFAULT 1"),
            new ColumnDefinition("t_agent_model_config", "compaction_prune", "TINYINT NOT NULL DEFAULT 0"),
            new ColumnDefinition("t_agent_model_config", "compaction_tail_turns", "INT NOT NULL DEFAULT 2"),
            new ColumnDefinition("t_agent_model_config", "compaction_preserve_recent_tokens", "INT DEFAULT NULL"),
            new ColumnDefinition("t_agent_model_config", "compaction_reserved_tokens", "INT DEFAULT NULL"),
            new ColumnDefinition("t_agent_model_config", "compaction_model_config_id", "INT DEFAULT NULL"),
            new ColumnDefinition("t_agent_model_config", "compaction_threshold_percent", "INT NOT NULL DEFAULT 90"),
            new ColumnDefinition("t_agent_model_config", "api_key_encrypted", "VARCHAR(2048) DEFAULT NULL"),
            new ColumnDefinition("t_agent_model_config", "api_key_key_version", "VARCHAR(64) DEFAULT NULL"),
            new ColumnDefinition("t_agent_mcp_server", "auth_header_encrypted", "VARCHAR(2048) DEFAULT NULL"),
            new ColumnDefinition("t_agent_mcp_server", "auth_header_key_version", "VARCHAR(64) DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "run_version", "BIGINT NOT NULL DEFAULT 0"),
            new ColumnDefinition("t_agent_task", "last_event_sequence", "BIGINT NOT NULL DEFAULT 0"),
            new ColumnDefinition("t_agent_task", "request_payload", "LONGTEXT DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "recovery_attempts", "INT NOT NULL DEFAULT 0"),
            new ColumnDefinition("t_agent_task", "retry_attempts", "INT NOT NULL DEFAULT 0"),
            new ColumnDefinition("t_agent_task", "next_retry_at", "DATETIME(3) DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "execution_epoch", "BIGINT NOT NULL DEFAULT 0"),
            new ColumnDefinition("t_agent_task", "execution_owner", "VARCHAR(128) DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "execution_lease_expires_at", "DATETIME(3) DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "execution_heartbeat_at", "DATETIME(3) DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "background_branch", "VARCHAR(160) DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "background_worktree", "VARCHAR(2048) DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "background_base_ref", "VARCHAR(128) DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "background_cleanup_status", "VARCHAR(32) DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "submitted_at", "DATETIME(3) DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "started_at", "DATETIME(3) DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "active_segment_started_at", "DATETIME(3) DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "finished_at", "DATETIME(3) DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "elapsed_ms", "BIGINT DEFAULT NULL"),
            new ColumnDefinition("t_agent_task", "active_elapsed_ms", "BIGINT NOT NULL DEFAULT 0"));

    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    @Autowired
    public AdditiveSchemaMigrator(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    public void migrate() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            log.info("Running additive schema migration against database catalog '{}' ({})",
                    catalog == null || catalog.isBlank() ? "<default>" : catalog,
                    metadata.getDatabaseProductName());
            for (ColumnDefinition column : REQUIRED_COLUMNS) {
                addColumnIfMissing(metadata, catalog, column);
            }
            createCommandAuditTableIfMissing(metadata, catalog);
            createProjectCheckoutLeaseTableIfMissing(metadata, catalog);
            addIndexIfMissing(metadata, catalog, "t_agent_task", "idx_task_retry_due", "status, next_retry_at");
            addIndexIfMissing(metadata, catalog, "t_agent_task", "idx_task_execution_lease", "execution_lease_expires_at");
            addIndexIfMissing(metadata, catalog, "t_agent_conversation", "idx_conv_project_updated", "student_id, project_id, status, update_time");
            addIndexIfMissing(metadata, catalog, "t_agent_message", "idx_msg_conversation_history", "conversation_id, student_id, project_id, message_id");
        }
    }


    private void addIndexIfMissing(DatabaseMetaData metadata, String catalog, String table, String index, String columns) throws SQLException {
        if (indexExists(metadata, catalog, table, index) || indexExists(metadata, null, table, index)) return;
        try { jdbcTemplate.execute("ALTER TABLE " + table + " ADD INDEX " + index + " (" + columns + ")"); }
        catch (RuntimeException failure) { if (isDuplicateIndexError(failure)) return; throw failure; }
    }
    private boolean indexExists(DatabaseMetaData metadata, String catalog, String table, String index) throws SQLException {
        try (ResultSet indexes = metadata.getIndexInfo(catalog, null, table, false, false)) {
            if (indexes == null) return false;
            while (indexes.next()) if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
            return false;
        }
    }
    private boolean isDuplicateIndexError(Throwable failure) {
        for (Throwable cause=failure; cause!=null; cause=cause.getCause()) if (cause instanceof SQLException sql) {
            String message=sql.getMessage()==null?"":sql.getMessage().toLowerCase(Locale.ROOT);
            if (sql.getErrorCode()==1061 || message.contains("duplicate key name") || message.contains("already exists")) return true;
        }
        return false;
    }

    private void createProjectCheckoutLeaseTableIfMissing(DatabaseMetaData metadata, String catalog) throws SQLException {
        if (tableExists(metadata, catalog, "t_agent_project_checkout_lease")) {
            return;
        }
        String createSql = """
                CREATE TABLE t_agent_project_checkout_lease (
                    checkout_key CHAR(64) NOT NULL PRIMARY KEY,
                    project_id INT NOT NULL,
                    workspace_path VARCHAR(2048) NOT NULL,
                    task_id BIGINT NOT NULL,
                    lease_owner VARCHAR(128) NOT NULL,
                    lease_epoch BIGINT NOT NULL DEFAULT 1,
                    lease_expires_at DATETIME(3) NOT NULL,
                    heartbeat_at DATETIME(3) NOT NULL,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    INDEX idx_agent_checkout_lease_expiry (lease_expires_at),
                    INDEX idx_agent_checkout_lease_task (task_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
        try {
            jdbcTemplate.execute(createSql);
        } catch (RuntimeException failure) {
            if (isTableAlreadyExists(failure) && tableExists(metadata, catalog, "t_agent_project_checkout_lease")) {
                log.info("Project checkout lease table was created concurrently; continuing additive schema migration");
                return;
            }
            throw failure;
        }
    }

    private void createCommandAuditTableIfMissing(DatabaseMetaData metadata, String catalog) throws SQLException {
        if (tableExists(metadata, catalog, "t_command_audit_event")) {
            return;
        }
        String createSql = """
                CREATE TABLE t_command_audit_event (
                    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                    approval_id VARCHAR(64) NOT NULL,
                    student_id INT NOT NULL,
                    project_id INT NOT NULL,
                    task_id BIGINT NOT NULL,
                    conversation_id VARCHAR(64) DEFAULT NULL,
                    session_id VARCHAR(128) DEFAULT NULL,
                    event_type VARCHAR(48) NOT NULL,
                    source VARCHAR(64) NOT NULL,
                    invocation_id VARCHAR(128) NOT NULL,
                    tool_call_id VARCHAR(128) NOT NULL,
                    command_digest VARCHAR(64) NOT NULL,
                    classification VARCHAR(64) NOT NULL,
                    policy_version VARCHAR(64) NOT NULL,
                    decision VARCHAR(32) DEFAULT NULL,
                    execution_status VARCHAR(32) DEFAULT NULL,
                    exit_code INT DEFAULT NULL,
                    duration_ms BIGINT DEFAULT NULL,
                    output_digest VARCHAR(64) DEFAULT NULL,
                    output_size_bytes BIGINT DEFAULT NULL,
                    idempotency_key VARCHAR(192) NOT NULL,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_command_audit_approval_idempotency (approval_id, idempotency_key),
                    INDEX idx_command_audit_task (task_id, event_id),
                    INDEX idx_command_audit_owner (student_id, project_id, create_time),
                    INDEX idx_command_audit_approval_type (approval_id, event_type)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
        try {
            jdbcTemplate.execute(createSql);
        } catch (RuntimeException failure) {
            if (isTableAlreadyExists(failure) && tableExists(metadata, catalog, "t_command_audit_event")) {
                log.info("Command audit table was created concurrently; continuing additive schema migration");
                return;
            }
            throw failure;
        }
    }

    private boolean tableExists(DatabaseMetaData metadata, String catalog, String tableName) throws SQLException {
        if (tableExists(metadata, catalog, tableName, tableName.toUpperCase(Locale.ROOT))) {
            return true;
        }
        return tableExists(metadata, null, tableName, tableName.toUpperCase(Locale.ROOT));
    }

    private boolean tableExists(DatabaseMetaData metadata, String catalog, String... tableNames) throws SQLException {
        for (String tableName : tableNames) {
            try (ResultSet tables = metadata.getTables(catalog, null, tableName, new String[]{"TABLE"})) {
                if (tables != null && tables.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isTableAlreadyExists(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof SQLException sqlException)) {
                continue;
            }
            String state = sqlException.getSQLState();
            String message = sqlException.getMessage() == null ? "" : sqlException.getMessage().toLowerCase(Locale.ROOT);
            if (sqlException.getErrorCode() == 1050 || "42S01".equalsIgnoreCase(state)
                    || message.contains("table already exists")) {
                return true;
            }
        }
        return false;
    }

    private void addColumnIfMissing(DatabaseMetaData metadata, String catalog, ColumnDefinition column)
            throws SQLException {
        if (columnExists(metadata, catalog, column)) {
            return;
        }

        String alterSql = "ALTER TABLE " + column.tableName()
                + " ADD COLUMN " + column.columnName() + " " + column.definition();
        try {
            jdbcTemplate.execute(alterSql);
        } catch (RuntimeException e) {
            if (isDuplicateColumnError(e, column) && columnExists(metadata, catalog, column)) {
                log.info("Column {}.{} was added concurrently; continuing additive schema migration",
                        column.tableName(), column.columnName());
                return;
            }
            throw e;
        }
    }

    private boolean isDuplicateColumnError(Throwable failure, ColumnDefinition column) {
        String expectedColumnName = column.columnName().toLowerCase(Locale.ROOT);
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (!(cause instanceof SQLException sqlException)) {
                continue;
            }
            String sqlState = sqlException.getSQLState();
            String message = sqlException.getMessage();
            String normalizedMessage = message == null ? "" : message.toLowerCase(Locale.ROOT);
            boolean duplicateColumn = sqlException.getErrorCode() == 1060
                    || "42S21".equalsIgnoreCase(sqlState)
                    || "42701".equalsIgnoreCase(sqlState)
                    || normalizedMessage.contains("duplicate column")
                    || normalizedMessage.contains("column already exists");
            if (duplicateColumn && containsExactColumnName(normalizedMessage, expectedColumnName)) {
                return true;
            }
        }
        return false;
    }

    private boolean containsExactColumnName(String message, String expectedColumnName) {
        return Pattern.compile("(?<![a-z0-9_])" + Pattern.quote(expectedColumnName) + "(?![a-z0-9_])")
                .matcher(message)
                .find();
    }

    @PostConstruct
    void migrateOnStartup() throws SQLException {
        migrate();
    }

    private boolean columnExists(DatabaseMetaData metadata, String catalog, ColumnDefinition column) throws SQLException {
        if (columnExists(metadata, catalog, column.tableName(), column.columnName())) {
            return true;
        }
        return columnExists(metadata, null, column.tableName(), column.columnName());
    }

    private boolean columnExists(DatabaseMetaData metadata, String catalog, String tableName, String columnName)
            throws SQLException {
        try (ResultSet columns = metadata.getColumns(catalog, null, tableName, columnName)) {
            return columns.next();
        }
    }

    private record ColumnDefinition(String tableName, String columnName, String definition) {
    }
}
