package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentTask;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunInteractionMapper;
import com.labex.mapper.AgentRunOutboxMapper;
import com.labex.mapper.AgentTaskMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class AgentRunInteractionMapperDatabaseTest {

    @Test
    void selectsOnlyTheLatestResolvedInteractionForTasksThatStillWaitForThatInteractionType() throws Exception {
        DataSource dataSource = dataSource(UUID.randomUUID().toString());
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            List<AgentRunInteraction> candidates = session.getMapper(AgentRunInteractionMapper.class)
                    .selectResolvedAwaitingResume(100);

            assertThat(candidates)
                    .extracting(AgentRunInteraction::getInteractionId)
                    .containsExactly("question-current", "permission-current", "network-timed-out",
                            "question-concurrent", "timed-out-question");
        }
    }

    @Test
    void claimsTheLatestCompatibleResolvedInteractionAndMovesTheTaskToRecoveringInOneTransaction() throws Exception {
        DataSource dataSource = dataSource(UUID.randomUUID().toString());
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentRunLifecycleService.InteractionClaimOutcome outcome = lifecycle(session)
                    .claimResolvedInteractionDispatch(
                            71L, 7, 12, "question-current",
                            "Resuming after user response", "A persisted user response is ready",
                            AgentRunTransitionKey.forInteractionResume(71L, "question-current"),
                            "instance-a", 30_000L);
            session.commit();

            assertThat(outcome.claimed()).isTrue();
            assertThat(outcome.lease().taskId()).isEqualTo(71L);
            assertThat(outcome.lease().epoch()).isEqualTo(1L);
            assertThat(outcome.interaction().getInteractionId()).isEqualTo("question-current");
            assertThat(outcome.interaction().getResumeClaimId()).isNotBlank();
            assertThat(outcome.interaction().getResumeClaimEpoch()).isEqualTo(1L);
            assertThat(outcome.interaction().getResumeClaimedAt()).isNotNull();
            assertThat(outcome.interaction().getResumeConsumedAt()).isNull();

            AgentTask task = session.getMapper(AgentTaskMapper.class).selectById(71L);
            assertThat(task.getStatus()).isEqualTo("recovering");
            assertThat(task.getExecutionEpoch()).isEqualTo(1L);
            assertThat(task.getExecutionOwner()).isEqualTo("instance-a");
            assertThat(count(session, "t_agent_run_event", "task_id = 71")).isEqualTo(1);
            assertThat(count(session, "t_agent_run_outbox", "task_id = 71")).isEqualTo(1);
            assertThat(interactionRow(session, "question-current").getResumeClaimId())
                    .isEqualTo(outcome.interaction().getResumeClaimId());
        }
    }

    @Test
    void rejectsCrossOwnerCrossProjectExpiredAndNonLatestResumeClaims() throws Exception {
        DataSource dataSource = dataSource(UUID.randomUUID().toString());
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentRunLifecycleService lifecycle = lifecycle(session);

            assertThat(lifecycle.claimResolvedInteractionDispatch(
                    71L, 8, 12, "question-current",
                    "Resuming after user response", "A persisted user response is ready",
                    AgentRunTransitionKey.forInteractionResume(71L, "question-current"), "instance-a", 30_000L)
                    .claimed()).isFalse();
            assertThat(lifecycle.claimResolvedInteractionDispatch(
                    71L, 7, 13, "question-current",
                    "Resuming after user response", "A persisted user response is ready",
                    AgentRunTransitionKey.forInteractionResume(71L, "question-current"), "instance-a", 30_000L)
                    .claimed()).isFalse();
            assertThat(lifecycle.claimResolvedInteractionDispatch(
                    71L, 7, 12, "question-old",
                    "Resuming after user response", "A persisted user response is ready",
                    AgentRunTransitionKey.forInteractionResume(71L, "question-old"), "instance-a", 30_000L)
                    .claimed()).isFalse();
            assertThat(lifecycle.claimResolvedInteractionDispatch(
                    72L, 7, 12, "answered-before-new-wait",
                    "Resuming after user response", "A persisted user response is ready",
                    AgentRunTransitionKey.forInteractionResume(72L, "answered-before-new-wait"), "instance-a", 30_000L)
                    .claimed()).isFalse();
            assertThat(lifecycle.claimResolvedInteractionDispatch(
                    72L, 7, 12, "question-still-waiting",
                    "Resuming after user response", "A persisted user response is ready",
                    AgentRunTransitionKey.forInteractionResume(72L, "question-still-waiting"), "instance-a", 30_000L)
                    .claimed()).isFalse();
            assertThat(lifecycle.claimResolvedInteractionDispatch(
                    78L, 7, 12, "expired-answered",
                    "Resuming after user response", "A persisted user response is ready",
                    AgentRunTransitionKey.forInteractionResume(78L, "expired-answered"), "instance-a", 30_000L)
                    .claimed()).isFalse();
            session.commit();

            assertThat(session.getMapper(AgentTaskMapper.class).selectById(71L).getStatus()).isEqualTo("waiting_user");
            assertThat(session.getMapper(AgentTaskMapper.class).selectById(72L).getStatus()).isEqualTo("waiting_user");
            assertThat(count(session, "t_agent_run_event", "task_id IN (71, 72, 78)")).isZero();
            assertThat(count(session, "t_agent_run_outbox", "task_id IN (71, 72, 78)")).isZero();
        }
    }

    @Test
    void resumesATimedOutInteractionAsTheDurableResolutionOfAnExpiredOne() throws Exception {
        DataSource dataSource = dataSource(UUID.randomUUID().toString());
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentRunLifecycleService.InteractionClaimOutcome outcome = lifecycle(session)
                    .claimResolvedInteractionDispatch(
                            79L, 7, 12, "timed-out-question",
                            "Resuming after user response", "A persisted user response is ready",
                            AgentRunTransitionKey.forInteractionResume(79L, "timed-out-question"),
                            "instance-a", 30_000L);
            session.commit();

            assertThat(outcome.claimed()).isTrue();
            assertThat(outcome.interaction().getResumeClaimId()).isNotBlank();
        }
    }

    @Test
    void concurrentClaimsYieldExactlyOneDispatchClaim() throws Exception {
        DataSource dataSource = dataSource(UUID.randomUUID().toString());
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);
        String interactionId = "question-concurrent";
        String step = "Resuming after user response";
        String summary = "A persisted user response is ready";
        String idempotencyKey = AgentRunTransitionKey.forInteractionResume(77L, interactionId);

        try (SqlSession sessionA = sessionFactory.openSession(false);
             SqlSession sessionB = sessionFactory.openSession(false)) {
            AtomicReference<AgentRunLifecycleService.InteractionClaimOutcome> outcomeA = new AtomicReference<>();
            AtomicReference<AgentRunLifecycleService.InteractionClaimOutcome> outcomeB = new AtomicReference<>();
            AtomicReference<Throwable> failureB = new AtomicReference<>();
            CountDownLatch bGo = new CountDownLatch(1);
            Thread threadB = new Thread(() -> {
                try {
                    bGo.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                try {
                    outcomeB.set(lifecycle(sessionB).claimResolvedInteractionDispatch(
                            77L, 7, 12, interactionId, step, summary, idempotencyKey, "instance-b", 30_000L));
                    sessionB.commit();
                } catch (Throwable failure) {
                    failureB.set(failure);
                }
            }, "concurrent-claim-b");
            threadB.start();
            // A 先持有任务与交互行锁（未提交），B 随后启动并阻塞在 A 的锁上；
            // A 提交后 B 重新求值谓词，必然 claim 失败。
            outcomeA.set(lifecycle(sessionA).claimResolvedInteractionDispatch(
                    77L, 7, 12, interactionId, step, summary, idempotencyKey, "instance-a", 30_000L));
            bGo.countDown();
            Thread.sleep(300L);
            sessionA.commit();
            threadB.join(10_000L);

            assertThat(failureB.get()).isNull();
            assertThat(outcomeA.get()).isNotNull();
            assertThat(outcomeA.get().claimed()).isTrue();
            assertThat(outcomeB.get()).isNotNull();
            assertThat(outcomeB.get().claimed()).isFalse();
            assertThat(count(sessionB, "t_agent_run_event", "task_id = 77")).isEqualTo(1);
            assertThat(count(sessionB, "t_agent_run_outbox", "task_id = 77")).isEqualTo(1);
            assertThat(sessionB.getMapper(AgentTaskMapper.class).selectById(77L).getStatus()).isEqualTo("recovering");
            assertThat(interactionRow(sessionB, interactionId).getResumeClaimId())
                    .isEqualTo(outcomeA.get().interaction().getResumeClaimId());
        }
    }

    @Test
    void duplicateDispatchAfterTheFirstClaimReturnsTheFirstDurableResult() throws Exception {
        DataSource dataSource = dataSource(UUID.randomUUID().toString());
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentRunLifecycleService lifecycle = lifecycle(session);
            AgentRunLifecycleService.InteractionClaimOutcome first = lifecycle.claimResolvedInteractionDispatch(
                    71L, 7, 12, "question-current",
                    "Resuming after user response", "A persisted user response is ready",
                    AgentRunTransitionKey.forInteractionResume(71L, "question-current"), "instance-a", 30_000L);
            session.commit();

            AgentRunLifecycleService.InteractionClaimOutcome second = lifecycle.claimResolvedInteractionDispatch(
                    71L, 7, 12, "question-current",
                    "Resuming after user response", "A persisted user response is ready",
                    AgentRunTransitionKey.forInteractionResume(71L, "question-current"), "instance-a", 30_000L);
            session.commit();

            assertThat(first.claimed()).isTrue();
            assertThat(second.claimed()).isFalse();
            assertThat(count(session, "t_agent_run_event", "task_id = 71")).isEqualTo(1);
            assertThat(count(session, "t_agent_run_outbox", "task_id = 71")).isEqualTo(1);
            assertThat(interactionRow(session, "question-current").getResumeClaimId())
                    .isEqualTo(first.interaction().getResumeClaimId());
        }
    }

    @Test
    void marksAnOwnedDispatchClaimAsConsumedExactlyOnceAndKeepsItClaimed() throws Exception {
        DataSource dataSource = dataSource(UUID.randomUUID().toString());
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentRunLifecycleService.InteractionClaimOutcome claim = lifecycle(session)
                    .claimResolvedInteractionDispatch(
                            71L, 7, 12, "question-current",
                            "Resuming after user response", "A persisted user response is ready",
                            AgentRunTransitionKey.forInteractionResume(71L, "question-current"),
                            "instance-a", 30_000L);
            session.commit();
            AgentRunInteractionService interactions = new AgentRunInteractionService(
                    session.getMapper(AgentRunInteractionMapper.class));

            assertThat(interactions.markDispatchClaimConsumed(
                    "question-current", claim.interaction().getResumeClaimId())).isTrue();
            assertThat(interactions.markDispatchClaimConsumed(
                    "question-current", claim.interaction().getResumeClaimId())).isFalse();
            assertThat(interactions.markDispatchClaimConsumed("question-current", "foreign-claim")).isFalse();

            AgentRunInteraction consumed = interactionRow(session, "question-current");
            assertThat(consumed.getResumeClaimId()).isEqualTo(claim.interaction().getResumeClaimId());
            assertThat(consumed.getResumeConsumedAt()).isNotNull();
        }
    }

    private AgentRunLifecycleService lifecycle(SqlSession session) {
        return new AgentRunLifecycleService(
                session.getMapper(AgentTaskMapper.class),
                session.getMapper(AgentRunEventMapper.class),
                session.getMapper(AgentRunOutboxMapper.class),
                session.getMapper(AgentRunInteractionMapper.class));
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("interaction-claim", new JdbcTransactionFactory(), dataSource));
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

    private AgentRunInteraction interactionRow(SqlSession session, String interactionId) throws Exception {
        return session.getMapper(AgentRunInteractionMapper.class).selectById(interactionId);
    }

    private DataSource dataSource(String dbSuffix) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:resolved_interaction_claim_" + dbSuffix
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
                        runtime_profile VARCHAR(32),
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
            insertTask(statement, 71L, 7, 12, "waiting_user");
            insertTask(statement, 72L, 7, 12, "waiting_user");
            insertTask(statement, 73L, 7, 12, "waiting_approval");
            insertTask(statement, 74L, 7, 12, "completed");
            insertTask(statement, 75L, 7, 12, "waiting_user");
            insertTask(statement, 76L, 7, 12, "waiting_approval");
            insertTask(statement, 77L, 7, 12, "waiting_user");
            insertTask(statement, 78L, 7, 12, "waiting_user");
            insertTask(statement, 79L, 7, 12, "waiting_approval");
            insert(statement, "question-old", 71, "question", "answered", "2026-08-02 10:00:00", null);
            insert(statement, "question-current", 71, "question", "answered", "2026-08-02 10:01:00", null);
            insert(statement, "answered-before-new-wait", 72, "question", "answered", "2026-08-02 10:00:00", null);
            insert(statement, "question-still-waiting", 72, "question", "waiting", "2026-08-02 10:01:00", null);
            insert(statement, "permission-current", 73, "permission", "approved", "2026-08-02 10:02:00", null);
            insert(statement, "completed-question", 74, "question", "answered", "2026-08-02 10:03:00", null);
            insert(statement, "network-timed-out", 76, "network", "timed_out", "2026-08-02 10:05:00", null);
            statement.execute("INSERT INTO t_agent_run_interaction "
                    + "(interaction_id, task_id, student_id, project_id, interaction_type, status, idempotency_key, "
                    + "create_time, update_time) VALUES ('legacy-null-time', 75, 7, 12, 'question', "
                    + "'answered', 'key-legacy-null-time', NULL, TIMESTAMP '2026-08-02 10:00:00')");
            insert(statement, "question-after-null-time", 75, "question", "waiting", "2026-08-02 10:04:00", null);
            insert(statement, "question-concurrent", 77, "question", "answered", "2026-08-02 10:06:00", null);
            insert(statement, "expired-answered", 78, "question", "answered", "2026-08-02 10:07:00",
                    "2026-08-01 10:00:00");
            insert(statement, "timed-out-question", 79, "network", "timed_out", "2026-08-02 10:08:00",
                    "2026-08-01 10:00:00");
        }
    }

    private void insertTask(Statement statement, long taskId, int studentId, int projectId, String status)
            throws Exception {
        statement.execute("INSERT INTO t_agent_task "
                + "(task_id, student_id, project_id, status, run_version, last_event_sequence, execution_epoch, "
                + "update_time) VALUES (" + taskId + ", " + studentId + ", " + projectId + ", '" + status
                + "', 0, 0, 0, TIMESTAMP '2026-08-02 10:00:00')");
    }

    private void insert(Statement statement, String id, long taskId, String type, String status, String time,
                        String expiresTime) throws Exception {
        String expiry = expiresTime == null ? "NULL" : "TIMESTAMP '" + expiresTime + "'";
        statement.execute("INSERT INTO t_agent_run_interaction "
                + "(interaction_id, task_id, student_id, project_id, interaction_type, status, idempotency_key, "
                + "expires_time, create_time, update_time) VALUES ('" + id + "', " + taskId + ", 7, 12, '" + type
                + "', '" + status + "', 'key-" + id + "', " + expiry + ", TIMESTAMP '" + time
                + "', TIMESTAMP '" + time + "')");
    }
}
