package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.labex.entity.AgentProjectConfigAuditEvent;
import com.labex.mapper.AgentProjectConfigAuditEventMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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

class AgentProjectConfigAuditEventMapperDatabaseTest {

    @Test
    void appendsAuditEventsAndRoundTripsThroughSnakeCaseColumns() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigAuditEventMapper mapper = session.getMapper(AgentProjectConfigAuditEventMapper.class);
            mapper.insert(event(7, 12, "proposed", "event-1"));
            mapper.insert(event(7, 12, "applied", "event-2"));

            List<AgentProjectConfigAuditEvent> events = mapper.selectList(new QueryWrapper<AgentProjectConfigAuditEvent>()
                    .eq("project_id", 12).orderByAsc("event_id"));

            assertThat(events).hasSize(2);
            AgentProjectConfigAuditEvent loaded = events.get(0);
            assertThat(loaded.getEventId()).isNotNull();
            assertThat(loaded.getStudentId()).isEqualTo(7);
            assertThat(loaded.getProjectId()).isEqualTo(12);
            assertThat(loaded.getEventType()).isEqualTo("proposed");
            assertThat(loaded.getActor()).isEqualTo("student:7");
            assertThat(loaded.getReason()).isEqualTo("owner decision");
            assertThat(loaded.getPreviousStatus()).isEqualTo("pending");
            assertThat(loaded.getNextStatus()).isEqualTo("proposed");
            assertThat(loaded.getBeforeDigest()).isEqualTo("sha256:before");
            assertThat(loaded.getAfterDigest()).isEqualTo("sha256:after");
            assertThat(loaded.getChangedPathSummary()).isEqualTo("agent.json");
            assertThat(loaded.getTaskId()).isEqualTo(71L);
            assertThat(loaded.getExecutionEpoch()).isEqualTo(3L);
            assertThat(loaded.getIdempotencyKey()).isEqualTo("event-1");
            assertThat(loaded.getCreateTime()).isNotNull();
        }
    }

    @Test
    void rejectsDuplicateIdempotencyKeyPerProject() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigAuditEventMapper mapper = session.getMapper(AgentProjectConfigAuditEventMapper.class);
            mapper.insert(event(7, 12, "proposed", "event-1"));
            mapper.insert(event(8, 99, "proposed", "event-1"));

            Throwable duplicate = catchThrowable(() -> mapper.insert(event(7, 12, "approved", "event-1")));
            assertThat(uniqueConstraintViolated(duplicate))
                    .as("duplicate (project_id, idempotency_key) is rejected").isTrue();

            assertThat(mapper.selectCount(new QueryWrapper<AgentProjectConfigAuditEvent>()
                    .eq("project_id", 12))).isEqualTo(1);
        }
    }

    @Test
    void auditRowsAreAppendOnlyAndRejectUpdateAndDelete() throws Exception {
        DataSource dataSource = dataSource();
        createTable(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentProjectConfigAuditEventMapper mapper = session.getMapper(AgentProjectConfigAuditEventMapper.class);
            mapper.insert(event(7, 12, "proposed", "event-1"));

            AgentProjectConfigAuditEvent loaded = mapper.selectOne(new QueryWrapper<AgentProjectConfigAuditEvent>()
                    .eq("project_id", 12));
            AgentProjectConfigAuditEvent mutated = new AgentProjectConfigAuditEvent();
            mutated.setEventId(loaded.getEventId());
            mutated.setReason("tampered");

            Throwable updateFailure = catchThrowable(() -> mapper.updateById(mutated));
            assertThat(updateFailure).as("audit update is rejected").isNotNull();
            assertThat(immutableRejectionMessage(updateFailure)).contains("immutable");

            Throwable deleteFailure = catchThrowable(() -> mapper.deleteById(loaded.getEventId()));
            assertThat(deleteFailure).as("audit delete is rejected").isNotNull();
            assertThat(immutableRejectionMessage(deleteFailure)).contains("immutable");

            List<AgentProjectConfigAuditEvent> remaining = mapper.selectList(
                    new QueryWrapper<AgentProjectConfigAuditEvent>().eq("project_id", 12));
            assertThat(remaining).hasSize(1);
            assertThat(remaining.get(0).getEventType()).isEqualTo("proposed");
            assertThat(remaining.get(0).getReason()).isEqualTo("owner decision");
        }
    }

    @Test
    void schemaDefinesAppendOnlyAuditStorageWithoutSecrets() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/sql/schema.sql"));
        String block = tableBlock(schema, "t_agent_project_config_audit_event");

        assertThat(block)
                .contains("CREATE TABLE IF NOT EXISTS t_agent_project_config_audit_event (")
                .contains("student_id INT NOT NULL")
                .contains("project_id INT NOT NULL")
                .contains("event_type VARCHAR(48) NOT NULL")
                .contains("actor VARCHAR(128) DEFAULT NULL")
                .contains("reason VARCHAR(2048) DEFAULT NULL")
                .contains("previous_status VARCHAR(32) DEFAULT NULL")
                .contains("next_status VARCHAR(32) DEFAULT NULL")
                .contains("before_digest VARCHAR(64) DEFAULT NULL")
                .contains("after_digest VARCHAR(64) DEFAULT NULL")
                .contains("changed_path_summary TEXT DEFAULT NULL")
                .contains("task_id BIGINT DEFAULT NULL")
                .contains("execution_epoch BIGINT DEFAULT NULL")
                .contains("idempotency_key VARCHAR(192) NOT NULL")
                .contains("create_time DATETIME DEFAULT CURRENT_TIMESTAMP")
                .contains("UNIQUE KEY uk_agent_project_config_audit_idempotency (project_id, idempotency_key)")
                .contains("INDEX idx_agent_project_config_audit_owner (student_id, project_id, create_time)")
                .contains("INDEX idx_agent_project_config_audit_proposal (project_id, event_type, create_time)")
                .contains("INDEX idx_agent_project_config_audit_task (task_id, execution_epoch)");
        assertThat(block.toLowerCase(Locale.ROOT))
                .as("audit rows must be immutable: no update timestamp or on-update mutation")
                .doesNotContain("update_time", "on update")
                .doesNotContain("api_key", "auth_header", "password", "secret_value",
                        "access_token", "refresh_token", "credential_value", "private_key");
    }

    private AgentProjectConfigAuditEvent event(int studentId, int projectId, String type, String key) {
        AgentProjectConfigAuditEvent entity = new AgentProjectConfigAuditEvent();
        entity.setStudentId(studentId);
        entity.setProjectId(projectId);
        entity.setEventType(type);
        entity.setActor("student:" + studentId);
        entity.setReason("owner decision");
        entity.setPreviousStatus("pending");
        entity.setNextStatus(type);
        entity.setBeforeDigest("sha256:before");
        entity.setAfterDigest("sha256:after");
        entity.setChangedPathSummary("agent.json");
        entity.setTaskId(71L);
        entity.setExecutionEpoch(3L);
        entity.setIdempotencyKey(key);
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

    private String immutableRejectionMessage(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            String message = cause.getMessage();
            if (message != null && message.contains("immutable")) {
                return message;
            }
        }
        return "";
    }

    private String tableBlock(String schema, String table) {
        Matcher matcher = Pattern.compile("(?s)CREATE TABLE IF NOT EXISTS " + table + " .*?;")
                .matcher(schema);
        assertThat(matcher.find()).as("schema.sql must define %s", table).isTrue();
        return matcher.group();
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("audit-mapper", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentProjectConfigAuditEventMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:project_config_audit_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createTable(DataSource dataSource) throws SQLException {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_project_config_audit_event (
                        event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        event_type VARCHAR(48) NOT NULL,
                        actor VARCHAR(128) DEFAULT NULL,
                        reason VARCHAR(2048) DEFAULT NULL,
                        previous_status VARCHAR(32) DEFAULT NULL,
                        next_status VARCHAR(32) DEFAULT NULL,
                        before_digest VARCHAR(64) DEFAULT NULL,
                        after_digest VARCHAR(64) DEFAULT NULL,
                        changed_path_summary TEXT DEFAULT NULL,
                        task_id BIGINT DEFAULT NULL,
                        execution_epoch BIGINT DEFAULT NULL,
                        idempotency_key VARCHAR(192) NOT NULL,
                        create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
                        UNIQUE KEY uk_agent_project_config_audit_idempotency (project_id, idempotency_key),
                        INDEX idx_agent_project_config_audit_owner (student_id, project_id, create_time),
                        INDEX idx_agent_project_config_audit_proposal (project_id, event_type, create_time),
                        INDEX idx_agent_project_config_audit_task (task_id, execution_epoch)
                    )
                    """);
            statement.execute("CREATE TRIGGER trg_agent_audit_event_no_update BEFORE UPDATE "
                    + "ON t_agent_project_config_audit_event FOR EACH ROW CALL '"
                    + RejectAuditMutation.class.getName() + "'");
            statement.execute("CREATE TRIGGER trg_agent_audit_event_no_delete BEFORE DELETE "
                    + "ON t_agent_project_config_audit_event FOR EACH ROW CALL '"
                    + RejectAuditMutation.class.getName() + "'");
        }
    }

    public static final class RejectAuditMutation implements org.h2.api.Trigger {
        public RejectAuditMutation() {
        }

        @Override
        public void init(Connection connection, String schemaName, String triggerName,
                         String tableName, boolean before, int type) {
        }

        @Override
        public void fire(Connection connection, Object[] oldRow, Object[] newRow) throws SQLException {
            throw new SQLException("audit rows are immutable", "45000");
        }

        @Override
        public void close() {
        }

        @Override
        public void remove() {
        }
    }
}
