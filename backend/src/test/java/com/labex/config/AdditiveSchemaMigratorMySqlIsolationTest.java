package com.labex.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * B1 同步供给索引的**补齐 + 重复执行**验证（隔离库）。
 *
 * <p>为什么需要它：真实库里的两个索引现在存在，但"它们已经存在"并不能证明
 * "缺失时启动期迁移会补上"，也不能证明"再跑一次不会重复/报错"。
 * 这里在**隔离库**里用 schema.sql 建出与生产同构的表，然后**主动删掉这两个索引**制造
 * "缺失"场景（只动隔离库，绝不动真实库），再跑真实 {@link AdditiveSchemaMigrator}：
 * <ol>
 *   <li>第一次：必须把两个索引建出来；</li>
 *   <li>第二次：必须是干净的 no-op（幂等），效果快照完全一致。</li>
 * </ol>
 */
class AdditiveSchemaMigratorMySqlIsolationTest {

    private static final String CS_INDEX = "idx_cs_project_set";
    private static final String FC_INDEX = "idx_fc_project_update";
    private static final List<String> CREATED_SCHEMAS = new ArrayList<>();

    private static String adminUrl;
    private static String adminUser;
    private static String adminPassword;
    private static String schemaName;
    private static String isoUrl;

    @BeforeAll
    static void prepareIsolatedSchemaWithIndexesRemoved() throws Exception {
        String password = System.getenv("LABEX_AGENT_DB_PASSWORD");
        assumeTrue(password != null && !password.isBlank(), "缺少 LABEX_AGENT_DB_PASSWORD，跳过真实 MySQL 验证");
        adminPassword = password;
        adminUser = orDefault(System.getenv("LABEX_AGENT_DB_USERNAME"), "root");
        String rawUrl = orDefault(System.getenv("LABEX_AGENT_DB_URL"),
                "jdbc:mysql://localhost:3306/labex_agent?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true");
        String hostAndDb = rawUrl.substring("jdbc:mysql://".length());
        String hostPort = hostAndDb.substring(0, hostAndDb.indexOf('/'));
        String query = hostAndDb.contains("?") ? hostAndDb.substring(hostAndDb.indexOf('?') + 1) : "";
        adminUrl = "jdbc:mysql://" + hostPort + "/?" + query;
        schemaName = "labex_sync_idx_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        isoUrl = "jdbc:mysql://" + hostPort + "/" + schemaName + (query.isEmpty() ? "" : "?" + query);

        try (Connection connection = DriverManager.getConnection(adminUrl, adminUser, adminPassword);
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE DATABASE " + schemaName + " DEFAULT CHARSET utf8mb4 COLLATE utf8mb4_unicode_ci");
            CREATED_SCHEMAS.add(schemaName);
            statement.execute("USE " + schemaName);
            for (String ddl : schemaStatements()) {
                statement.execute(ddl);
            }
            // 制造"缺失索引"场景（仅隔离库）。
            statement.execute("ALTER TABLE t_agent_change_set DROP INDEX " + CS_INDEX);
            statement.execute("ALTER TABLE t_agent_file_change DROP INDEX " + FC_INDEX);
        }
    }

    @AfterAll
    static void dropIsolatedSchema() throws Exception {
        if (CREATED_SCHEMAS.isEmpty()) {
            return;
        }
        try (Connection connection = DriverManager.getConnection(adminUrl, adminUser, adminPassword);
             Statement statement = connection.createStatement()) {
            for (String schema : CREATED_SCHEMAS) {
                statement.execute("DROP DATABASE IF EXISTS " + schema);
            }
        }
    }

    /** 读取项目自己的 schema.sql：只取 CREATE TABLE 语句（文件里没有 INSERT/TRIGGER/PROCEDURE）。 */
    private static List<String> schemaStatements() throws Exception {
        Path schemaFile = Path.of("src", "main", "resources", "sql", "schema.sql");
        String raw = Files.readString(schemaFile, StandardCharsets.UTF_8);
        List<String> statements = new ArrayList<>();
        for (String chunk : raw.split(";\\s*\\r?\\n")) {
            String withoutComments = chunk.replaceAll("(?m)^\\s*--.*$", "").trim();
            if (withoutComments.regionMatches(true, 0, "CREATE TABLE", 0, "CREATE TABLE".length())) {
                statements.add(withoutComments);
            }
        }
        assertTrue(statements.size() > 40, "schema.sql 解析出的建表语句过少：" + statements.size());
        return statements;
    }

    private static DataSource isolatedDataSource() {
        com.mysql.cj.jdbc.MysqlDataSource dataSource = new com.mysql.cj.jdbc.MysqlDataSource();
        dataSource.setURL(isoUrl);
        dataSource.setUser(adminUser);
        dataSource.setPassword(adminPassword);
        return dataSource;
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean indexExists(String table, String index) throws Exception {
        try (Connection connection = DriverManager.getConnection(isoUrl, adminUser, adminPassword);
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.statistics"
                             + " WHERE table_schema = ? AND table_name = ? AND index_name = ?")) {
            statement.setString(1, schemaName);
            statement.setString(2, table);
            statement.setString(3, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                resultSet.next();
                return resultSet.getInt(1) > 0;
            }
        }
    }

    /** 迁移前后的“效果快照”：两张表的列 + 索引名集合（用于断言第二次执行零变化）。 */
    private String schemaSnapshot(String table) throws Exception {
        try (Connection connection = DriverManager.getConnection(isoUrl, adminUser, adminPassword);
             java.sql.PreparedStatement statement = connection.prepareStatement(
                     "SELECT column_name, column_type FROM information_schema.columns"
                             + " WHERE table_schema = ? AND table_name = ? ORDER BY column_name")) {
            statement.setString(1, schemaName);
            statement.setString(2, table);
            StringBuilder builder = new StringBuilder();
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    builder.append(resultSet.getString(1)).append(':').append(resultSet.getString(2)).append('|');
                }
            }
            try (java.sql.PreparedStatement indexes = connection.prepareStatement(
                    "SELECT DISTINCT index_name FROM information_schema.statistics"
                            + " WHERE table_schema = ? AND table_name = ? ORDER BY index_name")) {
                indexes.setString(1, schemaName);
                indexes.setString(2, table);
                try (ResultSet resultSet = indexes.executeQuery()) {
                    while (resultSet.next()) {
                        builder.append('#').append(resultSet.getString(1));
                    }
                }
            }
            return builder.toString();
        }
    }

    /** 记录实际执行的 SQL：用于证明“第一次确实建了索引、第二次完全不再重复建”。 */
    private static final class RecordingJdbcTemplate extends JdbcTemplate {
        private final List<String> executed = new ArrayList<>();

        RecordingJdbcTemplate(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public void execute(String sql) {
            executed.add(sql);
            super.execute(sql);
        }

        List<String> statements() {
            return executed;
        }

        String catalog() {
            try (Connection connection = getDataSource().getConnection()) {
                return connection.getCatalog();
            } catch (Exception error) {
                return "unknown";
            }
        }
    }

    @Test
    void addsMissingSyncFeedIndexesAndIsIdempotentOnRepeat() throws Exception {
        DataSource dataSource = isolatedDataSource();
        RecordingJdbcTemplate jdbcTemplate = new RecordingJdbcTemplate(dataSource);
        AdditiveSchemaMigrator migrator = new AdditiveSchemaMigrator(jdbcTemplate, dataSource);

        // 前置：两个索引确实不存在（场景成立）
        assertFalse(indexExists("t_agent_change_set", CS_INDEX), "隔离库里 " + CS_INDEX + " 应已被删除");
        assertFalse(indexExists("t_agent_file_change", FC_INDEX), "隔离库里 " + FC_INDEX + " 应已被删除");

        migrator.migrate();
        List<String> firstRunDdl = List.copyOf(jdbcTemplate.statements());
        System.out.println("[evidence] 第一次迁移实际执行的 DDL：" + firstRunDdl);

        // 第一次：必须真的执行了这两条 ADD INDEX —— 这是"补齐"的直接证据，
        // 而不是"索引后来又存在了"这种间接推断。
        assertTrue(firstRunDdl.stream().anyMatch(sql -> sql.contains("ADD INDEX " + CS_INDEX)),
                "第一次迁移必须执行创建 " + CS_INDEX + " 的 DDL，实际：" + firstRunDdl);
        assertTrue(firstRunDdl.stream().anyMatch(sql -> sql.contains("ADD INDEX " + FC_INDEX)),
                "第一次迁移必须执行创建 " + FC_INDEX + " 的 DDL，实际：" + firstRunDdl);
        assertTrue(indexExists("t_agent_change_set", CS_INDEX), "第一次迁移必须补齐 " + CS_INDEX);
        assertTrue(indexExists("t_agent_file_change", FC_INDEX), "第一次迁移必须补齐 " + FC_INDEX);
        String afterFirst = schemaSnapshot("t_agent_file_change") + schemaSnapshot("t_agent_change_set");

        // 第二次：必须干净幂等（不抛错、结构快照不变）
        jdbcTemplate.statements().clear();
        migrator.migrate();
        System.out.println("[evidence] 第二次迁移实际执行的 DDL：" + jdbcTemplate.statements());
        String afterSecond = schemaSnapshot("t_agent_file_change") + schemaSnapshot("t_agent_change_set");

        assertEquals(afterFirst, afterSecond, "重复执行迁移不得改变结构（幂等）");
        assertFalse(jdbcTemplate.statements().stream().anyMatch(sql -> sql.contains(CS_INDEX) || sql.contains(FC_INDEX)),
                "第二次迁移不得再执行与本索引相关的 DDL，实际：" + jdbcTemplate.statements());
        assertTrue(indexExists("t_agent_change_set", CS_INDEX));
        assertTrue(indexExists("t_agent_file_change", FC_INDEX));
    }
}
