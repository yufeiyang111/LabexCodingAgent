package com.labex.auth.config;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Locale;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** 认证域自己的加性迁移，不把认证字段耦合进 Agent 通用迁移器。 */
@Component
@DependsOn("dataSourceScriptDatabaseInitializer")
public class AuthSchemaMigrator {
    private static final Logger log = LoggerFactory.getLogger(AuthSchemaMigrator.class);
    private final JdbcTemplate jdbcTemplate;
    private final DataSource dataSource;

    public AuthSchemaMigrator(JdbcTemplate jdbcTemplate, DataSource dataSource) {
        this.jdbcTemplate = jdbcTemplate;
        this.dataSource = dataSource;
    }

    @PostConstruct
    void migrateOnStartup() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String catalog = connection.getCatalog();
            addEmailColumn(metadata, catalog);
            createOAuthBindingTable(metadata, catalog);
        }
    }

    private void addEmailColumn(DatabaseMetaData metadata, String catalog) throws SQLException {
        if (!columnExists(metadata, catalog, "t_user", "email")) {
            try {
                jdbcTemplate.execute("ALTER TABLE t_user ADD COLUMN email VARCHAR(254) DEFAULT NULL");
            } catch (RuntimeException failure) {
                if (!isAlreadyExists(failure)) {
                    throw failure;
                }
                log.info("Authentication email column was created concurrently; continuing migration");
            }
        }
        addIndexIfMissing(metadata, catalog, "t_user", "uk_user_email", "email", true);
    }

    private void createOAuthBindingTable(DatabaseMetaData metadata, String catalog) throws SQLException {
        if (tableExists(metadata, catalog, "t_user_oauth_binding")) {
            ensureNoDuplicateUserProviderBindings();
            addIndexIfMissing(metadata, catalog, "t_user_oauth_binding", "uk_user_oauth_user_provider",
                    "user_id, provider", true);
            return;
        }
        String sql = """
                CREATE TABLE t_user_oauth_binding (
                    binding_id INT AUTO_INCREMENT PRIMARY KEY,
                    user_id INT NOT NULL,
                    provider VARCHAR(32) NOT NULL,
                    subject VARCHAR(191) NOT NULL,
                    provider_email VARCHAR(254) DEFAULT NULL,
                    display_name VARCHAR(100) DEFAULT NULL,
                    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                    UNIQUE KEY uk_user_oauth_provider_subject (provider, subject),
                    UNIQUE KEY uk_user_oauth_user_provider (user_id, provider),
                    INDEX idx_user_oauth_user (user_id)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
                """;
        try {
            jdbcTemplate.execute(sql);
        } catch (RuntimeException failure) {
            if (isAlreadyExists(failure) && tableExists(metadata, catalog, "t_user_oauth_binding")) {
                log.info("Authentication OAuth binding table was created concurrently; continuing migration");
                return;
            }
            throw failure;
        }
    }

    private void ensureNoDuplicateUserProviderBindings() {
        Integer duplicateGroups = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM (
                    SELECT user_id, provider
                    FROM t_user_oauth_binding
                    GROUP BY user_id, provider
                    HAVING COUNT(*) > 1
                ) duplicate_groups
                """, Integer.class);
        if (duplicateGroups != null && duplicateGroups > 0) {
            throw new IllegalStateException(
                    "t_user_oauth_binding 存在同一用户重复 provider 绑定，已停止创建唯一约束；请先人工清理重复数据");
        }
    }

    private void addIndexIfMissing(DatabaseMetaData metadata, String catalog, String table,
                                   String index, String column, boolean unique) throws SQLException {
        if (indexExists(metadata, catalog, table, index)
                || indexExists(metadata, catalog, table.toUpperCase(Locale.ROOT), index)) {
            return;
        }
        String kind = unique ? "UNIQUE " : "";
        try {
            jdbcTemplate.execute("CREATE " + kind + "INDEX " + index + " ON " + table + " (" + column + ")");
        } catch (RuntimeException failure) {
            if (!isAlreadyExists(failure)) {
                throw failure;
            }
        }
    }

    private boolean columnExists(DatabaseMetaData metadata, String catalog, String table, String column) throws SQLException {
        try (ResultSet result = metadata.getColumns(catalog, null, table, column)) {
            if (result != null && result.next()) {
                return true;
            }
        }
        try (ResultSet result = metadata.getColumns(catalog, null, table.toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT))) {
            return result != null && result.next();
        }
    }

    private boolean tableExists(DatabaseMetaData metadata, String catalog, String table) throws SQLException {
        try (ResultSet result = metadata.getTables(catalog, null, table, new String[]{"TABLE"})) {
            if (result != null && result.next()) {
                return true;
            }
        }
        try (ResultSet result = metadata.getTables(catalog, null, table.toUpperCase(Locale.ROOT), new String[]{"TABLE"})) {
            return result != null && result.next();
        }
    }

    private boolean indexExists(DatabaseMetaData metadata, String catalog, String table, String index) throws SQLException {
        try (ResultSet result = metadata.getIndexInfo(catalog, null, table, false, false)) {
            while (result != null && result.next()) {
                if (index.equalsIgnoreCase(result.getString("INDEX_NAME"))) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean isAlreadyExists(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String message = sql.getMessage() == null ? "" : sql.getMessage().toLowerCase(Locale.ROOT);
                if (sql.getErrorCode() == 1050 || sql.getErrorCode() == 1060 || sql.getErrorCode() == 1061
                        || "42S01".equalsIgnoreCase(sql.getSQLState())
                        || "42S21".equalsIgnoreCase(sql.getSQLState())
                        || message.contains("already exists") || message.contains("duplicate")) {
                    return true;
                }
            }
        }
        return false;
    }
}
