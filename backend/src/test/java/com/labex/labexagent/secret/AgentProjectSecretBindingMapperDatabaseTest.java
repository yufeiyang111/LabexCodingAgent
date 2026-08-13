package com.labex.labexagent.secret;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentProjectSecretBinding;
import com.labex.mapper.AgentProjectSecretBindingMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class AgentProjectSecretBindingMapperDatabaseTest {

    @Test
    void roundTripsABindingThroughSnakeCaseColumns() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectSecretBindingMapper mapper = session.getMapper(AgentProjectSecretBindingMapper.class);
            mapper.insert(binding(7, 12, 88L, "MCP_TS_CREDENTIAL", "input-1",
                    "local-v1.iv.ciphertext-sentinel", 3600,
                    LocalDateTime.of(2026, 8, 12, 10, 30, 0)));

            AgentProjectSecretBinding loaded = byIdempotencyKey(mapper, 12, "input-1");
            assertThat(loaded.getStudentId()).isEqualTo(7);
            assertThat(loaded.getProjectId()).isEqualTo(12);
            assertThat(loaded.getProposalId()).isEqualTo(88L);
            assertThat(loaded.getFieldId()).isEqualTo("MCP_TS_CREDENTIAL");
            assertThat(loaded.getAlias()).isEqualTo("p12_MCP_TS_CREDENTIAL");
            assertThat(loaded.getEncryptedValue()).isEqualTo("local-v1.iv.ciphertext-sentinel");
            assertThat(loaded.getKeyVersion()).isEqualTo("local-v1");
            assertThat(loaded.getTtlSeconds()).isEqualTo(3600);
            assertThat(loaded.getExpiresAt()).isEqualTo(LocalDateTime.of(2026, 8, 12, 10, 30, 0));
            assertThat(loaded.getConfigured()).isEqualTo(1);
            assertThat(loaded.getCreateTime()).isNotNull();
            assertThat(loaded.getUpdateTime()).isNotNull();
        }
    }

    @Test
    void rejectsDuplicateIdempotencyKeyPerProject() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectSecretBindingMapper mapper = session.getMapper(AgentProjectSecretBindingMapper.class);
            mapper.insert(binding(7, 12, 88L, "field-a", "input-1",
                    "local-v1.iv.ciphertext-a", 3600, LocalDateTime.now().plusMinutes(5)));
            mapper.insert(binding(8, 99, 91L, "field-b", "input-1",
                    "local-v1.iv.ciphertext-b", 3600, LocalDateTime.now().plusMinutes(5)));

            Throwable duplicate = catchThrowable(() -> mapper.insert(binding(7, 12, 88L, "field-c", "input-1",
                    "local-v1.iv.ciphertext-c", 3600, LocalDateTime.now().plusMinutes(5))));
            assertThat(uniqueConstraintViolated(duplicate))
                    .as("duplicate (project_id, idempotency_key) is rejected").isTrue();

            assertThat(mapper.selectCount(new QueryWrapper<AgentProjectSecretBinding>()
                    .eq("project_id", 12))).isEqualTo(1);
            assertThat(mapper.selectCount(new QueryWrapper<AgentProjectSecretBinding>()
                    .eq("project_id", 99))).isEqualTo(1);
        }
    }

    @Test
    void rejectsDuplicateAliasPerProject() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectSecretBindingMapper mapper = session.getMapper(AgentProjectSecretBindingMapper.class);
            mapper.insert(binding(7, 12, 88L, "field-a", "input-1",
                    "local-v1.iv.ciphertext-a", 3600, LocalDateTime.now().plusMinutes(5)));

            Throwable duplicate = catchThrowable(() -> mapper.insert(binding(7, 12, 88L, "field-a", "input-2",
                    "local-v1.iv.ciphertext-b", 3600, LocalDateTime.now().plusMinutes(5))));
            assertThat(uniqueConstraintViolated(duplicate))
                    .as("duplicate (project_id, alias) is rejected").isTrue();

            AgentProjectSecretBinding otherProject = binding(8, 99, 91L, "field-a", "input-3",
                    "local-v1.iv.ciphertext-c", 3600, LocalDateTime.now().plusMinutes(5));
            mapper.insert(otherProject);
            assertThat(mapper.selectCount(new QueryWrapper<AgentProjectSecretBinding>()
                    .eq("project_id", 99))).isEqualTo(1);
        }
    }

    @Test
    void storesMultipleDistinctFieldsWithoutFalseCollisions() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectSecretBindingMapper mapper = session.getMapper(AgentProjectSecretBindingMapper.class);
            mapper.insert(binding(7, 12, 88L, "field-a", "input-1",
                    "local-v1.iv.ciphertext-a", 60, LocalDateTime.now().plusMinutes(1)));
            mapper.insert(binding(7, 12, 88L, "field-b", "input-2",
                    "local-v1.iv.ciphertext-b", 3600, LocalDateTime.now().plusHours(1)));

            List<AgentProjectSecretBinding> stored = mapper.selectList(
                    new QueryWrapper<AgentProjectSecretBinding>().eq("project_id", 12).orderByAsc("binding_id"));
            assertThat(stored).hasSize(2);
            assertThat(stored).extracting(AgentProjectSecretBinding::getFieldId)
                    .containsExactly("field-a", "field-b");
            assertThat(stored).extracting(AgentProjectSecretBinding::getTtlSeconds)
                    .containsExactly(60, 3600);
        }
    }

    @Test
    void schemaDefinesBindingStorageWithoutPlaintextColumns() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/sql/schema.sql"));
        String block = tableBlock(schema, "t_agent_project_secret_binding");

        assertThat(block)
                .contains("CREATE TABLE IF NOT EXISTS t_agent_project_secret_binding (")
                .contains("student_id INT NOT NULL")
                .contains("project_id INT NOT NULL")
                .contains("proposal_id BIGINT NOT NULL")
                .contains("field_id VARCHAR(128) NOT NULL")
                .contains("alias VARCHAR(128) NOT NULL")
                .contains("encrypted_value VARCHAR(2048) NOT NULL")
                .contains("key_version VARCHAR(64) NOT NULL")
                .contains("ttl_seconds INT NOT NULL")
                .contains("expires_at DATETIME(3) NOT NULL")
                .contains("idempotency_key VARCHAR(192) NOT NULL")
                .contains("configured TINYINT NOT NULL DEFAULT 1")
                .contains("UNIQUE KEY uk_agent_project_secret_binding_key (project_id, idempotency_key)")
                .contains("UNIQUE KEY uk_agent_project_secret_binding_alias (project_id, alias)")
                .contains("INDEX idx_agent_project_secret_binding_owner (student_id, project_id, field_id)")
                .contains("INDEX idx_agent_project_secret_binding_expiry (expires_at)");
        assertThat(block.toLowerCase(Locale.ROOT))
                .doesNotContain("api_key", "auth_header", "password", "secret_value",
                        "access_token", "refresh_token", "credential_value", "private_key",
                        "plaintext", "plain_value", "secret_plain");
    }

    private AgentProjectSecretBinding binding(int studentId, int projectId, long proposalId, String fieldId,
                                              String idempotencyKey, String ciphertext, int ttlSeconds,
                                              LocalDateTime expiresAt) {
        AgentProjectSecretBinding entity = new AgentProjectSecretBinding();
        entity.setStudentId(studentId);
        entity.setProjectId(projectId);
        entity.setProposalId(proposalId);
        entity.setFieldId(fieldId);
        entity.setAlias("p" + projectId + "_" + fieldId);
        entity.setEncryptedValue(ciphertext);
        entity.setKeyVersion("local-v1");
        entity.setTtlSeconds(ttlSeconds);
        entity.setExpiresAt(expiresAt);
        entity.setIdempotencyKey(idempotencyKey);
        entity.setConfigured(1);
        return entity;
    }

    private AgentProjectSecretBinding byIdempotencyKey(AgentProjectSecretBindingMapper mapper, int projectId,
                                                       String key) {
        return mapper.selectOne(new QueryWrapper<AgentProjectSecretBinding>()
                .eq("project_id", projectId)
                .eq("idempotency_key", key));
    }

    private boolean uniqueConstraintViolated(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private String tableBlock(String schema, String table) {
        Matcher matcher = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + table + " .*?;")
                .matcher(schema);
        assertThat(matcher.find()).as("schema.sql must define %s", table).isTrue();
        return matcher.group();
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("secret-binding-mapper", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentProjectSecretBindingMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:secret_binding_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createTable(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_project_secret_binding (
                        binding_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        proposal_id BIGINT NOT NULL,
                        field_id VARCHAR(128) NOT NULL,
                        alias VARCHAR(128) NOT NULL,
                        encrypted_value VARCHAR(2048) NOT NULL,
                        key_version VARCHAR(64) NOT NULL,
                        ttl_seconds INT NOT NULL,
                        expires_at DATETIME(3) NOT NULL,
                        idempotency_key VARCHAR(192) NOT NULL,
                        configured TINYINT NOT NULL DEFAULT 1,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_agent_project_secret_binding_key (project_id, idempotency_key),
                        UNIQUE KEY uk_agent_project_secret_binding_alias (project_id, alias),
                        INDEX idx_agent_project_secret_binding_owner (student_id, project_id, field_id),
                        INDEX idx_agent_project_secret_binding_expiry (expires_at)
                    )
                    """);
        }
    }
}
