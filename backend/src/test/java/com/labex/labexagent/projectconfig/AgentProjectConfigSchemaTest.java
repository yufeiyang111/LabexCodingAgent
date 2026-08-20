package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;

import com.labex.config.AdditiveSchemaMigrator;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class AgentProjectConfigSchemaTest {

    private static final Pattern TABLE_NAME = Pattern.compile(
            "(?i)^CREATE\\s+TABLE\\s+(?:IF\\s+NOT\\s+EXISTS\\s+)?([a-z0-9_]+)\\s*\\(");
    private static final Pattern INDEX_DEF = Pattern.compile(
            "(?i)^(INDEX|UNIQUE\\s+KEY)\\s+([a-z0-9_]+)\\s*\\(([^)]*)\\)\\s*,?$");
    private static final Set<String> NEW_TABLES = Set.of(
            "t_agent_project_config_revision",
            "t_agent_run_config_snapshot",
            "t_agent_project_config_external_change");

    @Test
    void definesProjectConfigRevisionStorage() throws Exception {
        String schema = schemaSql();

        assertThat(schema)
                .contains("CREATE TABLE IF NOT EXISTS t_agent_project_config_revision (")
                .contains("student_id INT NOT NULL")
                .contains("project_id INT NOT NULL")
                .contains("revision BIGINT NOT NULL")
                .contains("config_digest VARCHAR(64) NOT NULL")
                .contains("tree_reference VARCHAR(2048) DEFAULT NULL")
                .contains("schema_version VARCHAR(32) NOT NULL DEFAULT '1'")
                .contains("normalized_config LONGTEXT NOT NULL")
                .contains("validation_status VARCHAR(32) DEFAULT NULL")
                .contains("source_actor VARCHAR(128) DEFAULT NULL")
                .contains("create_time DATETIME DEFAULT CURRENT_TIMESTAMP")
                .contains("UNIQUE KEY uk_agent_project_config_revision (project_id, revision)")
                .contains("INDEX idx_agent_project_config_revision_owner (student_id, project_id, revision)")
                .contains("INDEX idx_agent_project_config_revision_digest (project_id, config_digest)");
    }

    @Test
    void definesRunConfigSnapshotStorage() throws Exception {
        String schema = schemaSql();

        assertThat(schema)
                .contains("CREATE TABLE IF NOT EXISTS t_agent_run_config_snapshot (")
                .contains("task_id BIGINT NOT NULL")
                .contains("execution_epoch BIGINT NOT NULL")
                .contains("project_id INT NOT NULL")
                .contains("project_config_revision BIGINT DEFAULT NULL")
                .contains("project_config_digest VARCHAR(64) DEFAULT NULL")
                .contains("effective_config_json LONGTEXT NOT NULL")
                .contains("effective_config_digest VARCHAR(64) NOT NULL")
                .contains("model_fingerprint VARCHAR(256) DEFAULT NULL")
                .contains("capability_digest VARCHAR(64) DEFAULT NULL")
                .contains("resource_digest VARCHAR(64) DEFAULT NULL")
                .contains("runtime_profile VARCHAR(64) DEFAULT NULL")
                .contains("network_policy_json LONGTEXT DEFAULT NULL")
                .contains("verification_policy_json LONGTEXT DEFAULT NULL")
                .contains("environment_operation_ref VARCHAR(64) DEFAULT NULL")
                .contains("secret_aliases_json LONGTEXT DEFAULT NULL")
                .contains("UNIQUE KEY uk_agent_run_config_snapshot_epoch (task_id, execution_epoch)")
                .contains("INDEX idx_agent_run_config_snapshot_task_project (task_id, project_id)");
    }

    @Test
    void definesExternalChangeReviewStorage() throws Exception {
        String schema = schemaSql();

        assertThat(schema)
                .contains("CREATE TABLE IF NOT EXISTS t_agent_project_config_external_change (")
                .contains("student_id INT NOT NULL")
                .contains("project_id INT NOT NULL")
                .contains("base_revision BIGINT NOT NULL")
                .contains("observed_tree_digest VARCHAR(64) NOT NULL")
                .contains("changed_path_summary TEXT DEFAULT NULL")
                .contains("status VARCHAR(32) NOT NULL DEFAULT 'external_change_pending'")
                .contains("proposal_id BIGINT DEFAULT NULL")
                .contains("detected_at DATETIME(3) DEFAULT NULL")
                .contains("UNIQUE KEY uk_agent_external_change_pending "
                        + "(project_id, base_revision, observed_tree_digest, status)")
                .contains("INDEX idx_agent_external_change_owner (student_id, project_id, status, detected_at)");
    }

    @Test
    void bootstrapSchemaNeverEmitsDestructiveSql() throws Exception {
        String upper = schemaSql().toUpperCase(Locale.ROOT);

        assertThat(upper)
                .doesNotContain("DROP TABLE", "DROP INDEX", "DROP COLUMN", "TRUNCATE", "RENAME TABLE");
    }

    @Test
    void newTablesNeverCarrySecretValuesOrRawProviderCredentials() throws Exception {
        String schema = schemaSql();

        assertThat(tableBlock(schema, "t_agent_run_config_snapshot").toLowerCase(Locale.ROOT))
                .contains("secret_aliases_json");
        for (String table : NEW_TABLES) {
            String block = tableBlock(schema, table).toLowerCase(Locale.ROOT);
            assertThat(block)
                    .doesNotContain("api_key", "auth_header", "password", "secret_value",
                            "access_token", "refresh_token", "credential_value", "private_key");
        }
    }

    @Test
    void freshSchemaMigrationIsIdempotentAndPreservesExistingData() throws Exception {
        JdbcDataSource dataSource = h2DataSource("project_config_fresh_");
        execute(dataSource, toH2Statements(schemaSql(), Set.of()));
        seedNewTables(dataSource);

        RecordingDataSource recording = new RecordingDataSource(dataSource);
        AdditiveSchemaMigrator migrator = new AdditiveSchemaMigrator(new JdbcTemplate(recording), recording);
        migrator.migrate();
        migrator.migrate();

        assertThat(recording.statements()).isEmpty();
        assertThat(count(dataSource, "t_agent_project_config_revision")).isEqualTo(2);
        assertThat(count(dataSource, "t_agent_run_config_snapshot")).isEqualTo(2);
        assertThat(count(dataSource, "t_agent_project_config_external_change")).isEqualTo(2);
        assertThat(digest(dataSource, "t_agent_project_config_revision", "revision_id", "config_digest", 2L))
                .isEqualTo("sha256:rev-2");
        assertThat(digest(dataSource, "t_agent_run_config_snapshot", "snapshot_id",
                "effective_config_digest", 2L)).isEqualTo("snap-digest-2");
        assertThat(status(dataSource, "t_agent_project_config_external_change", "external_change_id", 2L))
                .isEqualTo("external_change_pending");
    }

    @Test
    void existingSchemaMigrationCreatesMissingTablesIdempotentlyWithoutDestructiveSql() throws Exception {
        JdbcDataSource dataSource = h2DataSource("project_config_existing_");
        execute(dataSource, toH2Statements(schemaSql(), NEW_TABLES));
        seedLegacyTaskRow(dataSource);

        RecordingDataSource recording = new RecordingDataSource(dataSource);
        AdditiveSchemaMigrator migrator = new AdditiveSchemaMigrator(new JdbcTemplate(recording), recording);
        migrator.migrate();
        migrator.migrate();

        assertThat(recording.statements())
                .filteredOn(statement -> statement.contains("CREATE TABLE"))
                .hasSize(3)
                .allMatch(statement -> statement.contains("t_agent_project_config_revision")
                        || statement.contains("t_agent_run_config_snapshot")
                        || statement.contains("t_agent_project_config_external_change"));
        assertThat(recording.statements())
                .noneMatch(statement -> statement.toUpperCase(Locale.ROOT).contains("DROP")
                        || statement.toUpperCase(Locale.ROOT).contains("TRUNCATE"));
        assertThat(count(dataSource, "t_agent_task")).isEqualTo(1);

        assertThat(columns(dataSource, "t_agent_project_config_revision"))
                .contains("revision_id", "student_id", "project_id", "revision", "config_digest",
                        "tree_reference", "schema_version", "normalized_config", "validation_status",
                        "source_actor", "create_time", "update_time");
        assertThat(constraints(dataSource, "t_agent_project_config_revision"))
                .contains("uk_agent_project_config_revision");
        assertThat(indexNames(dataSource, "t_agent_project_config_revision"))
                .contains("idx_agent_project_config_revision_owner",
                        "idx_agent_project_config_revision_digest");

        assertThat(columns(dataSource, "t_agent_run_config_snapshot"))
                .contains("snapshot_id", "task_id", "execution_epoch", "project_id",
                        "project_config_revision", "project_config_digest", "effective_config_json",
                        "effective_config_digest", "model_fingerprint", "capability_digest",
                        "resource_digest", "runtime_profile", "network_policy_json",
                        "verification_policy_json", "environment_operation_ref", "secret_aliases_json",
                        "create_time", "update_time");
        assertThat(constraints(dataSource, "t_agent_run_config_snapshot"))
                .contains("uk_agent_run_config_snapshot_epoch");
        assertThat(indexNames(dataSource, "t_agent_run_config_snapshot"))
                .contains("idx_agent_run_config_snapshot_task_project");

        assertThat(columns(dataSource, "t_agent_project_config_external_change"))
                .contains("external_change_id", "student_id", "project_id", "base_revision",
                        "observed_tree_digest", "changed_path_summary", "status", "proposal_id",
                        "detected_at", "create_time", "update_time");
        assertThat(constraints(dataSource, "t_agent_project_config_external_change"))
                .contains("uk_agent_external_change_pending");
        assertThat(indexNames(dataSource, "t_agent_project_config_external_change"))
                .contains("idx_agent_external_change_owner");
    }

    private String schemaSql() throws Exception {
        return Files.readString(Path.of("src/main/resources/sql/schema.sql"));
    }

    private String tableBlock(String schema, String table) {
        Matcher matcher = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + table + " .*?;")
                .matcher(schema);
        assertThat(matcher.find()).as("schema.sql must define %s", table).isTrue();
        return matcher.group();
    }

    private JdbcDataSource h2DataSource(String prefix) {
        JdbcDataSource dataSource = new JdbcDataSource();
        // DATABASE_TO_LOWER=TRUE：H2 默认把标识符存为大写，而 AdditiveSchemaMigrator 用
        // 小写 pattern 查 getColumns/getIndexInfo/getTables（MySQL 大小写不敏感所以没问题）；
        // 测试库必须让元数据匹配与迁移器一致。
        dataSource.setURL("jdbc:h2:mem:" + prefix + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void execute(DataSource dataSource, List<String> statements) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (String sql : statements) {
                statement.execute(sql);
            }
        }
    }

    private static List<String> toH2Statements(String mysqlSchema, Set<String> excludedTables) {
        List<String> statements = new ArrayList<>();
        for (String rawStatement : mysqlSchema.split(";")) {
            String statement = rawStatement
                    .replaceAll("(?i)\\s+ON\\s+UPDATE\\s+CURRENT_TIMESTAMP(\\(\\d+\\))?", "")
                    .replaceAll("(?i)CURRENT_TIMESTAMP\\(\\d+\\)", "CURRENT_TIMESTAMP")
                    .replaceAll("(?i),\\s*pattern\\(191\\)", "")
                    .replaceAll("(?i)\\s*ENGINE\\s*=\\s*\\S+.*$", "")
                    .trim();
            if (statement.isEmpty()) {
                continue;
            }
            if (!statement.toUpperCase(Locale.ROOT).startsWith("CREATE TABLE")) {
                statements.add(statement);
                continue;
            }
            Matcher tableMatcher = TABLE_NAME.matcher(statement);
            if (!tableMatcher.find()) {
                statements.add(statement);
                continue;
            }
            String table = tableMatcher.group(1).toLowerCase(Locale.ROOT);
            if (excludedTables.contains(table)) {
                continue;
            }
            statements.add(createTableForH2(statement, table));
            statements.addAll(indexesForH2(statement, table));
        }
        return statements;
    }

    private static String createTableForH2(String createTableStatement, String table) {
        StringBuilder body = new StringBuilder();
        for (String line : createTableStatement.split("\n")) {
            if (INDEX_DEF.matcher(line.trim()).matches()) {
                continue;
            }
            body.append(line).append("\n");
        }
        return body.toString().replaceAll("(?s)\\s*,\\s*\\)\\s*$", ")");
    }

    private static List<String> indexesForH2(String createTableStatement, String table) {
        List<String> indexes = new ArrayList<>();
        for (String line : createTableStatement.split("\n")) {
            Matcher matcher = INDEX_DEF.matcher(line.trim());
            if (!matcher.matches()) {
                continue;
            }
            String name = matcher.group(2);
            String columns = matcher.group(3);
            if ("INDEX".equalsIgnoreCase(matcher.group(1))) {
                indexes.add("CREATE INDEX " + name + " ON " + table + " (" + columns + ")");
            } else {
                indexes.add("CREATE UNIQUE INDEX " + name + " ON " + table + " (" + columns + ")");
            }
        }
        return indexes;
    }

    private void seedNewTables(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO t_agent_project_config_revision "
                    + "(student_id, project_id, revision, config_digest, schema_version, normalized_config) "
                    + "VALUES (7, 12, 1, 'sha256:rev-1', '1', '{\"agent\":\"default\"}')");
            statement.execute("INSERT INTO t_agent_project_config_revision "
                    + "(student_id, project_id, revision, config_digest, schema_version, normalized_config) "
                    + "VALUES (7, 12, 2, 'sha256:rev-2', '1', '{\"agent\":\"coding\"}')");
            statement.execute("INSERT INTO t_agent_run_config_snapshot "
                    + "(task_id, execution_epoch, project_id, project_config_revision, project_config_digest, "
                    + "effective_config_json, effective_config_digest, secret_aliases_json) "
                    + "VALUES (71, 1, 12, 1, 'sha256:rev-1', '{\"model\":\"gpt\"}', 'snap-digest-1', "
                    + "'[\"provider-key-alias\"]')");
            statement.execute("INSERT INTO t_agent_run_config_snapshot "
                    + "(task_id, execution_epoch, project_id, project_config_revision, project_config_digest, "
                    + "effective_config_json, effective_config_digest, secret_aliases_json) "
                    + "VALUES (71, 2, 12, 2, 'sha256:rev-2', '{\"model\":\"gpt\"}', 'snap-digest-2', "
                    + "'[\"provider-key-alias\"]')");
            statement.execute("INSERT INTO t_agent_project_config_external_change "
                    + "(student_id, project_id, base_revision, observed_tree_digest, changed_path_summary, status) "
                    + "VALUES (7, 12, 1, 'tree-digest-1', 'agent.json', 'external_change_pending')");
            statement.execute("INSERT INTO t_agent_project_config_external_change "
                    + "(student_id, project_id, base_revision, observed_tree_digest, changed_path_summary, status) "
                    + "VALUES (7, 12, 1, 'tree-digest-2', 'environment.json', 'external_change_pending')");
        }
    }

    private void seedLegacyTaskRow(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("INSERT INTO t_agent_task (task_id, conversation_id, student_id, project_id) "
                    + "VALUES (71, 'conv-71', 7, 12)");
        }
    }

    private int count(DataSource dataSource, String table) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private String digest(DataSource dataSource, String table, String idColumn, String digestColumn, long id)
            throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT " + digestColumn + " FROM " + table
                     + " WHERE " + idColumn + " = " + id)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private String status(DataSource dataSource, String table, String idColumn, long id) throws SQLException {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT status FROM " + table
                     + " WHERE " + idColumn + " = " + id)) {
            rows.next();
            return rows.getString(1);
        }
    }

    private Set<String> columns(DataSource dataSource, String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rows = connection.getMetaData().getColumns(null, null, table, null)) {
            while (rows.next()) {
                names.add(rows.getString("COLUMN_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private Set<String> constraints(DataSource dataSource, String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT CONSTRAINT_NAME "
                     + "FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS WHERE TABLE_NAME = '" + table + "'")) {
            while (rows.next()) {
                names.add(rows.getString(1).toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private Set<String> indexNames(DataSource dataSource, String table) throws SQLException {
        Set<String> names = new LinkedHashSet<>();
        try (Connection connection = dataSource.getConnection();
             ResultSet rows = connection.getMetaData().getIndexInfo(null, null, table, false, false)) {
            while (rows.next()) {
                names.add(rows.getString("INDEX_NAME").toLowerCase(Locale.ROOT));
            }
        }
        return names;
    }

    private static final class RecordingDataSource implements DataSource {
        private final DataSource delegate;
        private final List<String> statements = new ArrayList<>();

        RecordingDataSource(DataSource delegate) {
            this.delegate = delegate;
        }

        List<String> statements() {
            return statements;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return connectionProxy(delegate.getConnection());
        }

        private Connection connectionProxy(Connection target) {
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{Connection.class}, (connectionProxy, connectionMethod, connectionArgs) -> {
                        if ("createStatement".equals(connectionMethod.getName())) {
                            Statement statement = (Statement) connectionMethod.invoke(target, connectionArgs);
                            return Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{Statement.class}, (statementProxy, statementMethod, statementArgs) -> {
                                        if (("execute".equals(statementMethod.getName())
                                                || "executeUpdate".equals(statementMethod.getName()))
                                                && statementArgs != null && statementArgs.length > 0
                                                && statementArgs[0] instanceof String sql) {
                                            statements.add(sql);
                                        }
                                        return invokeTarget(statementMethod, statement, statementArgs);
                                    });
                        }
                        return invokeTarget(connectionMethod, target, connectionArgs);
                    });
        }

        private static Object invokeTarget(Method method, Object target, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (java.lang.reflect.InvocationTargetException invocationFailure) {
                throw invocationFailure.getCause();
            }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return connectionProxy(delegate.getConnection(username, password));
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }
}
