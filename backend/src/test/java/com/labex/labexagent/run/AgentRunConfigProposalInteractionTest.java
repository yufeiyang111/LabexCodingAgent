package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.labexagent.run.AgentRunLifecycleService.InteractionClaimOutcome;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunInteractionMapper;
import com.labex.mapper.AgentRunOutboxMapper;
import com.labex.mapper.AgentTaskMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

/**
 * Task 2.3: config_proposal interactions are durable wait projections owned by the proposal
 * authority. Only the decision path (AgentProjectConfigProposalService + the transactional
 * Task 0.3 interaction claim) may resume a task from a config_proposal interaction; the
 * generic resume scheduler scan never sees them, so a resolved interaction without a
 * successful proposal decision can never resume the task.
 */
class AgentRunConfigProposalInteractionTest {

    private static final String STEP = "Resuming after config proposal decision";
    private static final String SUMMARY = "A config proposal decision is ready";

    @Test
    void approvedConfigProposalClaimResumesTaskWithNewEpochInOneTransaction() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            InteractionClaimOutcome outcome = lifecycle(session).claimResolvedInteractionDispatch(
                    71L, 7, 12, "config-approved", STEP, SUMMARY,
                    AgentRunTransitionKey.forInteractionResume(71L, "config-approved"), "instance-a", 30_000L);
            session.commit();

            assertThat(outcome.claimed()).isTrue();
            assertThat(outcome.lease().taskId()).isEqualTo(71L);
            assertThat(outcome.lease().epoch()).isEqualTo(1L);
            assertThat(outcome.interaction().getInteractionType()).isEqualTo("config_proposal");
            assertThat(outcome.interaction().getResumeClaimId()).isNotBlank();
            assertThat(outcome.interaction().getResumeClaimEpoch()).isEqualTo(1L);
            assertThat(outcome.interaction().getResumeConsumedAt()).isNull();

            AgentTask task = session.getMapper(AgentTaskMapper.class).selectById(71L);
            assertThat(task.getStatus()).isEqualTo("recovering");
            assertThat(task.getExecutionEpoch()).isEqualTo(1L);
            assertThat(task.getExecutionOwner()).isEqualTo("instance-a");
            assertThat(count(session, "t_agent_run_event", "task_id = 71")).isEqualTo(1);
            assertThat(count(session, "t_agent_run_outbox", "task_id = 71")).isEqualTo(1);
        }
    }

    @Test
    void rejectedConfigProposalClaimResumesTaskWithNewEpoch() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            InteractionClaimOutcome outcome = lifecycle(session).claimResolvedInteractionDispatch(
                    72L, 7, 12, "config-rejected", STEP, SUMMARY,
                    AgentRunTransitionKey.forInteractionResume(72L, "config-rejected"), "instance-a", 30_000L);
            session.commit();

            assertThat(outcome.claimed()).isTrue();
            assertThat(outcome.lease().epoch()).isEqualTo(1L);
            AgentTask task = session.getMapper(AgentTaskMapper.class).selectById(72L);
            assertThat(task.getStatus()).isEqualTo("recovering");
            assertThat(task.getExecutionEpoch()).isEqualTo(1L);
        }
    }

    @Test
    void expiredConfigProposalInteractionClaimsAsTimedOutAndResumes() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            InteractionClaimOutcome outcome = lifecycle(session).claimResolvedInteractionDispatch(
                    73L, 7, 12, "config-timed-out", STEP, SUMMARY,
                    AgentRunTransitionKey.forInteractionResume(73L, "config-timed-out"), "instance-a", 30_000L);
            session.commit();

            assertThat(outcome.claimed()).isTrue();
            assertThat(outcome.interaction().getStatus()).isEqualTo("timed_out");
            assertThat(session.getMapper(AgentTaskMapper.class).selectById(73L).getStatus())
                    .isEqualTo("recovering");
        }
    }

    @Test
    void resolvedConfigProposalIsInvisibleToTheGenericResumeSchedulerScan() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            List<AgentRunInteraction> candidates = session.getMapper(AgentRunInteractionMapper.class)
                    .selectResolvedAwaitingResume(100);

            assertThat(candidates)
                    .extracting(AgentRunInteraction::getInteractionId)
                    .doesNotContain("config-approved", "config-rejected", "config-timed-out");
        }
    }

    @Test
    void configProposalClaimRejectsForeignStudentOrProject() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            assertThat(lifecycle(session).claimResolvedInteractionDispatch(
                    71L, 8, 12, "config-approved", STEP, SUMMARY,
                    AgentRunTransitionKey.forInteractionResume(71L, "config-approved"), "instance-a", 30_000L)
                    .claimed()).isFalse();
            assertThat(lifecycle(session).claimResolvedInteractionDispatch(
                    71L, 7, 13, "config-approved", STEP, SUMMARY,
                    AgentRunTransitionKey.forInteractionResume(71L, "config-approved"), "instance-a", 30_000L)
                    .claimed()).isFalse();
            session.commit();
        }
    }

    @Test
    void configProposalClaimRejectsWhenTheTaskIsNotWaitingForTheInteraction() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            assertThat(lifecycle(session).claimResolvedInteractionDispatch(
                    74L, 7, 12, "config-running", STEP, SUMMARY,
                    AgentRunTransitionKey.forInteractionResume(74L, "config-running"), "instance-a", 30_000L)
                    .claimed()).isFalse();
            session.commit();
            assertThat(session.getMapper(AgentTaskMapper.class).selectById(74L).getStatus())
                    .isEqualTo("running");
        }
    }

    @Test
    void duplicateDispatchAfterConsumptionKeepsTheFirstDurableResult() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentRunLifecycleService lifecycle = lifecycle(session);
            InteractionClaimOutcome first = lifecycle.claimResolvedInteractionDispatch(
                    71L, 7, 12, "config-approved", STEP, SUMMARY,
                    AgentRunTransitionKey.forInteractionResume(71L, "config-approved"), "instance-a", 30_000L);
            session.commit();
            AgentRunInteractionService interactions = new AgentRunInteractionService(
                    session.getMapper(AgentRunInteractionMapper.class));
            assertThat(interactions.markDispatchClaimConsumed(
                    "config-approved", first.interaction().getResumeClaimId())).isTrue();

            InteractionClaimOutcome replay = lifecycle.claimResolvedInteractionDispatch(
                    71L, 7, 12, "config-approved", STEP, SUMMARY,
                    AgentRunTransitionKey.forInteractionResume(71L, "config-approved"), "instance-b", 30_000L);
            session.commit();

            assertThat(replay.claimed()).isFalse();
            assertThat(replay.outcome()).isEqualTo(InteractionClaimOutcome.Outcome.REJECTED);
            AgentTask task = session.getMapper(AgentTaskMapper.class).selectById(71L);
            assertThat(task.getStatus()).isEqualTo("recovering");
            assertThat(task.getExecutionEpoch()).isEqualTo(1L);
            assertThat(task.getExecutionOwner()).isEqualTo("instance-a");
            assertThat(count(session, "t_agent_run_event", "task_id = 71")).isEqualTo(1);
        }
    }

    @Test
    void responseValidationAllowsOnlyApprovedAndRejectedForConfigProposal() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentRunInteractionService interactions = new AgentRunInteractionService(
                    session.getMapper(AgentRunInteractionMapper.class));

            assertThatThrownBy(() -> interactions.respond(7, 12, "config-waiting", "answered",
                    Map.of("answer", "yes")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Invalid resolution status");
            assertThat(interactions.respond(7, 12, "config-waiting", "approved",
                    Map.of("decision", "approved", "proposalId", 5L)).getStatus()).isEqualTo("approved");
            session.commit();

            assertThat(interactionRow(session, "config-waiting").getStatus()).isEqualTo("approved");
        }
    }

    @Test
    void waitingConfigProposalExpiresToTimedOutThroughTheExistingSweep() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentRunInteractionService interactions = new AgentRunInteractionService(
                    session.getMapper(AgentRunInteractionMapper.class));
            List<AgentRunInteraction> expired = interactions.claimExpired(
                    LocalDateTime.of(2026, 8, 2, 12, 0), 100);
            session.commit();

            assertThat(expired).extracting(AgentRunInteraction::getInteractionId)
                    .contains("config-sweep");
            assertThat(interactionRow(session, "config-sweep").getStatus()).isEqualTo("timed_out");
        }
    }

    @Test
    void configProposalEventsReplayAsDurableFactsForTheOwnedTaskOnly() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentRunLifecycleService lifecycle = lifecycle(session);
            Map<String, Object> created = Map.of(
                    "proposalId", 5L, "candidateConfigDigest", "digest-1",
                    "changedPathSummary", "agents/main.json", "taskId", 71L,
                    "executionEpoch", 0L, "toolCallId", "call-1");
            lifecycle.appendEvent(71L, "CONFIG_PROPOSAL_CREATED", created,
                    "config-proposal-created:v1:71:5");
            lifecycle.appendEvent(71L, "RUN_QUEUED", Map.of("queued", true),
                    "run-queued:71");
            session.commit();

            AgentRunEventReplayService replay = new AgentRunEventReplayService(
                    session.getMapper(AgentTaskMapper.class),
                    session.getMapper(AgentRunEventMapper.class));
            List<AgentRunEvent> events = replay.configProposalEvents(7, 12, 71L);

            assertThat(events).hasSize(1);
            assertThat(events.get(0).getEventType()).isEqualTo("CONFIG_PROPOSAL_CREATED");
            assertThat(events.get(0).getPayload()).contains("digest-1").contains("\"proposalId\":5")
                    .doesNotContain("secret");
            assertThatThrownBy(() -> replay.configProposalEvents(8, 12, 71L))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private AgentRunLifecycleService lifecycle(SqlSession session) {
        return new AgentRunLifecycleService(
                session.getMapper(AgentTaskMapper.class),
                session.getMapper(AgentRunEventMapper.class),
                session.getMapper(AgentRunOutboxMapper.class),
                session.getMapper(AgentRunInteractionMapper.class));
    }

    private AgentRunInteraction interactionRow(SqlSession session, String interactionId) throws Exception {
        return session.getMapper(AgentRunInteractionMapper.class).selectById(interactionId);
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("config-proposal-interaction", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMapper.class);
        configuration.addMapper(AgentRunEventMapper.class);
        configuration.addMapper(AgentRunOutboxMapper.class);
        configuration.addMapper(AgentRunInteractionMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private int count(SqlSession session, String table, String predicate) throws Exception {
        try (Statement statement = session.getConnection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:config_proposal_interaction_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createSchemaAndFixtures(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_task (
                        task_id BIGINT PRIMARY KEY,
                        conversation_id VARCHAR(64),
                        session_id VARCHAR(128),
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        title VARCHAR(256),
                        mode VARCHAR(32),
                        model_config_id INT DEFAULT NULL,
                        status VARCHAR(32) NOT NULL,
                        current_step VARCHAR(256),
                        summary VARCHAR(1024),
                        run_version BIGINT NOT NULL DEFAULT 0,
                        last_event_sequence BIGINT NOT NULL DEFAULT 0,
                        request_payload LONGTEXT,
                        recovery_attempts INT NOT NULL DEFAULT 0,
                        retry_attempts INT NOT NULL DEFAULT 0,
                        next_retry_at DATETIME(3),
                        execution_epoch BIGINT NOT NULL DEFAULT 0,
                        execution_owner VARCHAR(128),
                        execution_lease_expires_at DATETIME(3),
                        execution_heartbeat_at DATETIME(3),
                        background_branch VARCHAR(160),
                        background_worktree VARCHAR(2048),
                        background_base_ref VARCHAR(128),
                        background_cleanup_status VARCHAR(32),
                        submitted_at DATETIME(3),
                        started_at DATETIME(3),
                        active_segment_started_at DATETIME(3),
                        finished_at DATETIME(3),
                        elapsed_ms BIGINT,
                        active_elapsed_ms BIGINT NOT NULL DEFAULT 0,
                        create_time DATETIME,
                        update_time DATETIME NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE t_agent_run_interaction (
                        interaction_id VARCHAR(64) PRIMARY KEY,
                        task_id BIGINT NOT NULL,
                        conversation_id VARCHAR(64),
                        session_id VARCHAR(128),
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        interaction_type VARCHAR(32) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        request_payload LONGTEXT,
                        response_payload LONGTEXT,
                        idempotency_key VARCHAR(128) NOT NULL,
                        expires_time DATETIME,
                        resume_claim_id VARCHAR(64),
                        resume_claim_epoch BIGINT,
                        resume_claimed_at DATETIME(3),
                        resume_consumed_at DATETIME(3),
                        create_time DATETIME,
                        update_time DATETIME NOT NULL
                    )
                    """);
            statement.execute("""
                    CREATE TABLE t_agent_run_event (
                        event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        task_id BIGINT NOT NULL,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        sequence_number BIGINT NOT NULL,
                        state VARCHAR(32) NOT NULL,
                        event_type VARCHAR(80) NOT NULL,
                        payload LONGTEXT,
                        idempotency_key VARCHAR(128) NOT NULL,
                        create_time DATETIME,
                        UNIQUE (task_id, idempotency_key),
                        UNIQUE (task_id, sequence_number)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE t_agent_run_outbox (
                        outbox_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        event_id BIGINT NOT NULL,
                        task_id BIGINT NOT NULL,
                        topic VARCHAR(80) NOT NULL,
                        payload LONGTEXT NOT NULL,
                        status VARCHAR(32) NOT NULL DEFAULT 'pending',
                        attempts INT NOT NULL DEFAULT 0,
                        available_time DATETIME,
                        published_time DATETIME,
                        create_time DATETIME
                    )
                    """);
            insertTask(statement, 71L, "waiting_user");
            insertTask(statement, 72L, "waiting_user");
            insertTask(statement, 73L, "waiting_user");
            insertTask(statement, 74L, "running");
            insertInteraction(statement, "config-approved", 71, "config_proposal", "approved", null);
            insertInteraction(statement, "config-rejected", 72, "config_proposal", "rejected", null);
            insertInteraction(statement, "config-timed-out", 73, "config_proposal", "timed_out",
                    "2026-08-01 10:00:00");
            insertInteraction(statement, "config-running", 74, "config_proposal", "approved", null);
            insertInteraction(statement, "config-waiting", 75, "config_proposal", "waiting", null);
            insertInteraction(statement, "config-sweep", 76, "config_proposal", "waiting",
                    "2026-08-01 10:00:00");
        }
    }

    private void insertTask(Statement statement, long taskId, String status) throws Exception {
        statement.execute("INSERT INTO t_agent_task "
                + "(task_id, student_id, project_id, status, run_version, last_event_sequence, execution_epoch, "
                + "update_time) VALUES (" + taskId + ", 7, 12, '" + status
                + "', 0, 0, 0, TIMESTAMP '2026-08-02 10:00:00')");
    }

    private void insertInteraction(Statement statement, String id, long taskId, String type, String status,
                                   String expiresTime) throws Exception {
        String expiry = expiresTime == null ? "NULL" : "TIMESTAMP '" + expiresTime + "'";
        statement.execute("INSERT INTO t_agent_run_interaction "
                + "(interaction_id, task_id, student_id, project_id, interaction_type, status, request_payload, "
                + "idempotency_key, expires_time, create_time, update_time) VALUES ('" + id + "', " + taskId
                + ", 7, 12, '" + type + "', '" + status + "', '{\"proposalId\":5,\"candidateConfigDigest\":\"d1\","
                + "\"changedPathSummary\":\"agents/main.json\"}', 'key-" + id + "', " + expiry
                + ", TIMESTAMP '2026-08-02 10:00:00', TIMESTAMP '2026-08-02 10:00:00')");
    }
}
