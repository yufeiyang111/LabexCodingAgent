package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentProjectConfigProposal;
import com.labex.mapper.AgentProjectConfigProposalMapper;
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

class AgentProjectConfigProposalMapperDatabaseTest {

    @Test
    void roundTripsAProposalThroughSnakeCaseColumns() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigProposalMapper mapper = session.getMapper(AgentProjectConfigProposalMapper.class);
            AgentProjectConfigProposal fixture = proposal(7, 12, "key-1", 1);
            fixture.setDecisionIdempotencyKey("decision-1");
            fixture.setDecisionActor("student:7");
            fixture.setDecisionTime(LocalDateTime.of(2026, 8, 11, 10, 1, 30));
            fixture.setAppliedRevision(2L);
            mapper.insert(fixture);

            AgentProjectConfigProposal loaded = mapper.selectById(proposalId(mapper, 12, "key-1"));
            assertThat(loaded.getStudentId()).isEqualTo(7);
            assertThat(loaded.getProjectId()).isEqualTo(12);
            assertThat(loaded.getProposalKey()).isEqualTo("key-1");
            assertThat(loaded.getBaseRevision()).isEqualTo(1L);
            assertThat(loaded.getCandidateConfigDigest()).isEqualTo("sha256:key-1");
            assertThat(loaded.getPatchReference()).isEqualTo("git:main@9f86d081");
            assertThat(loaded.getChangedPathSummary()).isEqualTo("agent.json, environment.json");
            assertThat(loaded.getReason()).isEqualTo("switch runtime profile to strict");
            assertThat(loaded.getOriginTaskId()).isEqualTo(71L);
            assertThat(loaded.getOriginExecutionEpoch()).isEqualTo(3L);
            assertThat(loaded.getOriginToolCallId()).isEqualTo("tool-call-1");
            assertThat(loaded.getSource()).isEqualTo("agent");
            assertThat(loaded.getCreator()).isEqualTo("student:7");
            assertThat(loaded.getStatus()).isEqualTo("pending");
            assertThat(loaded.getExpiresTime()).isNotNull();
            assertThat(loaded.getDecisionIdempotencyKey()).isEqualTo("decision-1");
            assertThat(loaded.getDecisionActor()).isEqualTo("student:7");
            assertThat(loaded.getDecisionTime())
                    .isEqualTo(LocalDateTime.of(2026, 8, 11, 10, 1, 30));
            assertThat(loaded.getAppliedRevision()).isEqualTo(2L);
            assertThat(loaded.getCreateTime()).isNotNull();
        }
    }

    @Test
    void rejectsDuplicateProposalKeyPerProject() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigProposalMapper mapper = session.getMapper(AgentProjectConfigProposalMapper.class);
            mapper.insert(proposal(7, 12, "key-1", 1));
            mapper.insert(proposal(8, 99, "key-1", 1));

            Throwable duplicate = catchThrowable(() -> mapper.insert(proposal(7, 12, "key-1", 2)));
            assertThat(uniqueConstraintViolated(duplicate))
                    .as("duplicate (project_id, proposal_key) is rejected").isTrue();

            assertThat(mapper.selectCount(new QueryWrapper<AgentProjectConfigProposal>()
                    .eq("project_id", 12))).isEqualTo(1);
        }
    }

    @Test
    void rejectsDuplicateDecisionIdempotencyKeyPerProject() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigProposalMapper mapper = session.getMapper(AgentProjectConfigProposalMapper.class);
            AgentProjectConfigProposal first = proposal(7, 12, "key-a", 1);
            first.setDecisionIdempotencyKey("decision-1");
            mapper.insert(first);

            AgentProjectConfigProposal sameDecision = proposal(7, 12, "key-b", 1);
            sameDecision.setDecisionIdempotencyKey("decision-1");
            Throwable duplicate = catchThrowable(() -> mapper.insert(sameDecision));
            assertThat(uniqueConstraintViolated(duplicate))
                    .as("duplicate (project_id, decision_idempotency_key) is rejected").isTrue();

            AgentProjectConfigProposal otherProject = proposal(8, 99, "key-c", 1);
            otherProject.setDecisionIdempotencyKey("decision-1");
            mapper.insert(otherProject);
            assertThat(mapper.selectCount(new QueryWrapper<AgentProjectConfigProposal>()
                    .eq("project_id", 99))).isEqualTo(1);
        }
    }

    @Test
    void storesProposalsAgainstAStaleBaseRevisionWithoutFalseCollisions() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigProposalMapper mapper = session.getMapper(AgentProjectConfigProposalMapper.class);
            mapper.insert(proposal(7, 12, "key-a", 1));
            mapper.insert(proposal(7, 12, "key-b", 1));

            List<AgentProjectConfigProposal> stored = mapper.selectList(new QueryWrapper<AgentProjectConfigProposal>()
                    .eq("project_id", 12).orderByAsc("proposal_id"));
            assertThat(stored).hasSize(2);
            assertThat(stored).extracting(AgentProjectConfigProposal::getBaseRevision)
                    .containsExactly(1L, 1L);
        }
    }

    @Test
    void enforcesOneMinuteExpiryBoundary() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        LocalDateTime created = LocalDateTime.of(2026, 8, 11, 10, 0, 0);
        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigProposalMapper mapper = session.getMapper(AgentProjectConfigProposalMapper.class);

            AgentProjectConfigProposal boundary = proposal(7, 12, "boundary", 1);
            boundary.setCreateTime(created);
            boundary.setExpiresTime(created.plusMinutes(1));
            mapper.insert(boundary);

            AgentProjectConfigProposal over = proposal(7, 12, "over", 1);
            over.setCreateTime(created);
            over.setExpiresTime(created.plusSeconds(61));
            Throwable violation = catchThrowable(() -> mapper.insert(over));
            assertThat(checkConstraintViolated(violation))
                    .as("expiry beyond one minute is rejected").isTrue();

            AgentProjectConfigProposal shorter = proposal(7, 12, "shorter", 1);
            shorter.setCreateTime(created);
            shorter.setExpiresTime(created.plusSeconds(30));
            mapper.insert(shorter);

            AgentProjectConfigProposal loaded = mapper.selectById(proposalId(mapper, 12, "shorter"));
            assertThat(loaded.getExpiresTime()).isEqualTo(created.plusSeconds(30));
        }
    }

    @Test
    void schemaDefinesProposalStorageWithoutSecrets() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/sql/schema.sql"));
        String block = tableBlock(schema, "t_agent_project_config_proposal");

        assertThat(block)
                .contains("CREATE TABLE IF NOT EXISTS t_agent_project_config_proposal (")
                .contains("student_id INT NOT NULL")
                .contains("project_id INT NOT NULL")
                .contains("proposal_key VARCHAR(192) NOT NULL")
                .contains("base_revision BIGINT NOT NULL")
                .contains("candidate_config_digest VARCHAR(64) NOT NULL")
                .contains("patch_reference VARCHAR(2048) DEFAULT NULL")
                .contains("changed_path_summary TEXT DEFAULT NULL")
                .contains("origin_task_id BIGINT DEFAULT NULL")
                .contains("origin_execution_epoch BIGINT DEFAULT NULL")
                .contains("origin_tool_call_id VARCHAR(128) DEFAULT NULL")
                .contains("status VARCHAR(32) NOT NULL DEFAULT 'pending'")
                .contains("expires_time DATETIME NOT NULL")
                .contains("decision_idempotency_key VARCHAR(128) DEFAULT NULL")
                .contains("decision_actor VARCHAR(128) DEFAULT NULL")
                .contains("decision_time DATETIME(3) DEFAULT NULL")
                .contains("applied_revision BIGINT DEFAULT NULL")
                .contains("UNIQUE KEY uk_agent_project_config_proposal_key (project_id, proposal_key)")
                .contains("UNIQUE KEY uk_agent_project_config_proposal_decision "
                        + "(project_id, decision_idempotency_key)")
                .contains("INDEX idx_agent_project_config_proposal_owner "
                        + "(student_id, project_id, status, create_time)")
                .contains("INDEX idx_agent_project_config_proposal_expiry (status, expires_time)")
                .contains("CHECK (expires_time <= TIMESTAMPADD(MINUTE, 1, create_time))");
        assertThat(block.toLowerCase(Locale.ROOT))
                .doesNotContain("api_key", "auth_header", "password", "secret_value",
                        "access_token", "refresh_token", "credential_value", "private_key");
    }

    private Long proposalId(AgentProjectConfigProposalMapper mapper, int projectId, String key) {
        return mapper.selectOne(new QueryWrapper<AgentProjectConfigProposal>()
                .eq("project_id", projectId)
                .eq("proposal_key", key)).getProposalId();
    }

    private AgentProjectConfigProposal proposal(int studentId, int projectId, String key, long baseRevision) {
        AgentProjectConfigProposal entity = new AgentProjectConfigProposal();
        entity.setStudentId(studentId);
        entity.setProjectId(projectId);
        entity.setProposalKey(key);
        entity.setBaseRevision(baseRevision);
        entity.setCandidateConfigDigest("sha256:" + key);
        entity.setPatchReference("git:main@9f86d081");
        entity.setChangedPathSummary("agent.json, environment.json");
        entity.setReason("switch runtime profile to strict");
        entity.setOriginTaskId(71L);
        entity.setOriginExecutionEpoch(3L);
        entity.setOriginToolCallId("tool-call-1");
        entity.setSource("agent");
        entity.setCreator("student:" + studentId);
        entity.setStatus("pending");
        entity.setExpiresTime(LocalDateTime.now().plusMinutes(1));
        return entity;
    }

    private boolean uniqueConstraintViolated(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && "23505".equals(sql.getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private boolean checkConstraintViolated(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String message = sql.getMessage() == null ? "" : sql.getMessage().toLowerCase(Locale.ROOT);
                if ("23513".equals(sql.getSQLState()) || message.contains("check constraint")) {
                    return true;
                }
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
                new Environment("proposal-mapper", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentProjectConfigProposalMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:project_config_proposal_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createTable(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_project_config_proposal (
                        proposal_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        proposal_key VARCHAR(192) NOT NULL,
                        base_revision BIGINT NOT NULL,
                        candidate_config_digest VARCHAR(64) NOT NULL,
                        patch_reference VARCHAR(2048) DEFAULT NULL,
                        changed_path_summary TEXT DEFAULT NULL,
                        reason VARCHAR(2048) DEFAULT NULL,
                        origin_task_id BIGINT DEFAULT NULL,
                        origin_execution_epoch BIGINT DEFAULT NULL,
                        origin_tool_call_id VARCHAR(128) DEFAULT NULL,
                        source VARCHAR(64) NOT NULL DEFAULT 'agent',
                        creator VARCHAR(128) DEFAULT NULL,
                        status VARCHAR(32) NOT NULL DEFAULT 'pending',
                        expires_time DATETIME NOT NULL,
                        decision_idempotency_key VARCHAR(128) DEFAULT NULL,
                        decision_actor VARCHAR(128) DEFAULT NULL,
                        decision_time DATETIME(3) DEFAULT NULL,
                        applied_revision BIGINT DEFAULT NULL,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_agent_project_config_proposal_key (project_id, proposal_key),
                        UNIQUE KEY uk_agent_project_config_proposal_decision (project_id, decision_idempotency_key),
                        INDEX idx_agent_project_config_proposal_owner (student_id, project_id, status, create_time),
                        INDEX idx_agent_project_config_proposal_expiry (status, expires_time),
                        CHECK (expires_time <= TIMESTAMPADD(MINUTE, 1, create_time))
                    )
                    """);
        }
    }
}
