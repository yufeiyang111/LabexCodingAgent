package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunInteraction;
import com.labex.entity.AgentRunOutbox;
import com.labex.entity.AgentTask;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunInteractionMapper;
import com.labex.mapper.AgentRunOutboxMapper;
import com.labex.mapper.AgentTaskMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
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

class AgentRunInteractionDispatchClaimTest {

    @Test
    void resumesOnlyWhenTheInteractionClaimIsNewAndPassesItsLease() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentTask waiting = task("waiting_user");
        AgentTask recovering = task("recovering");
        AgentRunExecutionLeaseService.ExecutionLease lease = new AgentRunExecutionLeaseService.ExecutionLease(
                71L, "instance-a", 4L, LocalDateTime.of(2026, 7, 23, 10, 1));
        AgentRunInteraction interaction = interaction();
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(waiting, recovering);
        when(tasks.claimResolvedInteractionDispatch(eq(7), eq(12), eq(71L), eq("interaction-71"), any(), any()))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.claimed(lease, interaction));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean resumed = scheduler.resumeIfWaiting(interaction);

        assertThat(resumed).isTrue();
        verify(engine).resume(eq(7), eq(12), any(AgentStreamRequest.class), eq(71L), eq(true), eq(lease),
                eq(interaction));
    }

    @Test
    void doesNotResumeAnInteractionFromAnotherStudent() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunInteraction interaction = interaction();
        interaction.setStudentId(8);
        when(tasks.getOwnedTask(8, 12, 71L)).thenReturn(null);
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean resumed = scheduler.resumeIfWaiting(interaction);

        assertThat(resumed).isFalse();
        verify(engine, never()).resume(anyInt(), anyInt(), any(AgentStreamRequest.class), anyLong(), anyBoolean(), any());
    }

    @Test
    void doesNotResumeAnInteractionFromAnotherProject() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunInteraction interaction = interaction();
        interaction.setProjectId(13);
        when(tasks.getOwnedTask(7, 13, 71L)).thenReturn(null);
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean resumed = scheduler.resumeIfWaiting(interaction);

        assertThat(resumed).isFalse();
        verify(engine, never()).resume(anyInt(), anyInt(), any(AgentStreamRequest.class), anyLong(), anyBoolean(), any());
    }

    @Test
    void doesNotDispatchAnExpiredWaitingInteraction() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunInteraction interaction = interaction();
        interaction.setStatus("waiting");
        interaction.setExpiresTime(LocalDateTime.of(2026, 7, 23, 9, 0));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean resumed = scheduler.resumeIfWaiting(interaction);

        assertThat(resumed).isFalse();
        verify(engine, never()).resume(anyInt(), anyInt(), any(AgentStreamRequest.class), anyLong(), anyBoolean(), any());
    }

    @Test
    void doesNotDispatchAResolvedNonLatestInteractionWhileANewerOneIsWaiting() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        // SECURE TARGET: 最新性校验属于事务性 claim 的职责；claim 拒绝（null）时调度器必须 fail closed，
        // 不能凭已扫描到的交互 ID 自行重建 dispatch。
        when(tasks.getOwnedTask(7, 12, 71L)).thenReturn(task("waiting_user"), task("recovering"));
        when(tasks.claimResolvedInteractionDispatch(eq(7), eq(12), eq(71L), eq("interaction-71"), any(), any()))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.rejected());
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        boolean resumed = scheduler.resumeIfWaiting(interaction());

        // SECURE TARGET: 已解决但非最新的交互不能在有更新等待交互时触发恢复 dispatch
        assertThat(resumed).isFalse();
        verify(engine, never()).resume(anyInt(), anyInt(), any(AgentStreamRequest.class), anyLong(), anyBoolean(), any());
    }

    @Test
    void doesNotDispatchTwiceForTheSameResolvedInteraction() {
        AgentTaskService tasks = mock(AgentTaskService.class);
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunInteraction interaction = interaction();
        when(tasks.getOwnedTask(7, 12, 71L))
                .thenReturn(task("waiting_user"), task("recovering"), task("recovering"));
        when(tasks.claimResolvedInteractionDispatch(eq(7), eq(12), eq(71L), eq("interaction-71"), any(), any()))
                .thenReturn(AgentRunLifecycleService.InteractionClaimOutcome.claimed(lease(), interaction));
        AgentRunResumeScheduler scheduler = new AgentRunResumeScheduler(tasks, engine);

        scheduler.resumeIfWaiting(interaction);
        boolean second = scheduler.resumeIfWaiting(interaction);

        assertThat(second).isTrue();
        verify(engine, times(1)).resume(anyInt(), anyInt(), any(AgentStreamRequest.class), anyLong(), anyBoolean(),
                any(), any());
    }

    @Test
    void aSecondDispatchClaimForTheSameInteractionIsIdempotent() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        AgentRunOutboxMapper outbox = mock(AgentRunOutboxMapper.class);
        when(tasks.selectByTaskIdForUpdate(71L)).thenReturn(task("waiting_user"));
        when(tasks.update(isNull(), any())).thenReturn(1);
        List<AgentRunEvent> inserted = new ArrayList<>();
        when(events.selectOne(any())).thenAnswer(invocation -> inserted.isEmpty() ? null : inserted.get(0));
        when(events.selectMaxSequenceByTaskId(71L)).thenReturn(0L);
        doAnswer(invocation -> {
            AgentRunEvent event = invocation.getArgument(0);
            event.setEventId(1L);
            inserted.add(event);
            return 1;
        }).when(events).insert(any(AgentRunEvent.class));
        when(outbox.insert(any(AgentRunOutbox.class))).thenReturn(1);
        AgentRunLifecycleService lifecycle = new AgentRunLifecycleService(tasks, events, outbox);

        AgentRunLifecycleService.DispatchClaim first = lifecycle.claimDispatch(
                71L, AgentRunState.WAITING_USER, AgentRunState.RECOVERING, "RUN_INTERACTION_RESUME_QUEUED",
                Map.of(), "Resuming after user response", "A persisted user response is ready",
                AgentRunTransitionKey.forInteractionResume(71L, "interaction-71"), "instance-a", 30_000L);
        AgentRunLifecycleService.DispatchClaim second = lifecycle.claimDispatch(
                71L, AgentRunState.WAITING_USER, AgentRunState.RECOVERING, "RUN_INTERACTION_RESUME_QUEUED",
                Map.of(), "Resuming after user response", "A persisted user response is ready",
                AgentRunTransitionKey.forInteractionResume(71L, "interaction-71"), "instance-a", 30_000L);

        assertThat(first).isNotNull();
        assertThat(second).isNull();
        verify(tasks, times(1)).update(isNull(), any());
    }

    @Test
    void defersWhileALeaseIsActiveWithoutLeavingAnUnconsumedClaimAndClaimsAgainAfterExpiry() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = sessionFactory(dataSource);
        String step = "Resuming after user response";
        String summary = "A persisted user response is ready";
        String idempotencyKey = AgentRunTransitionKey.forInteractionResume(71L, "question-current");

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentRunLifecycleService lifecycle = new AgentRunLifecycleService(
                    session.getMapper(AgentTaskMapper.class),
                    session.getMapper(AgentRunEventMapper.class),
                    session.getMapper(AgentRunOutboxMapper.class),
                    session.getMapper(AgentRunInteractionMapper.class));

            // 任务已被另一个 worker 持有未过期租约：claim 必须 DEFERRED 且不写交互 claim。
            AgentRunLifecycleService.InteractionClaimOutcome deferred = lifecycle.claimResolvedInteractionDispatch(
                    71L, 7, 12, "question-current", step, summary, idempotencyKey, "instance-b", 30_000L);
            session.commit();

            assertThat(deferred.claimed()).isFalse();
            assertThat(deferred.outcome())
                    .isEqualTo(AgentRunLifecycleService.InteractionClaimOutcome.Outcome.DEFERRED);
            AgentRunInteraction untouched = session.getMapper(AgentRunInteractionMapper.class)
                    .selectById("question-current");
            assertThat(untouched.getResumeClaimId()).isNull();
            assertThat(untouched.getResumeClaimedAt()).isNull();
            assertThat(untouched.getResumeConsumedAt()).isNull();
            assertThat(session.getMapper(AgentTaskMapper.class).selectById(71L).getStatus())
                    .isEqualTo("waiting_user");
            assertThat(count(session, "t_agent_run_event", "task_id = 71")).isZero();
            assertThat(count(session, "t_agent_run_outbox", "task_id = 71")).isZero();

            // 旧 worker 租约过期后，同一交互必须重新可 claim，且只恢复一次。
            session.getConnection().createStatement().execute(
                    "UPDATE t_agent_task SET execution_lease_expires_at = TIMESTAMP '2026-08-01 10:00:00' "
                            + "WHERE task_id = 71");
            session.commit();
            AgentRunLifecycleService.InteractionClaimOutcome claimed = lifecycle.claimResolvedInteractionDispatch(
                    71L, 7, 12, "question-current", step, summary, idempotencyKey, "instance-b", 30_000L);
            session.commit();

            assertThat(claimed.claimed()).isTrue();
            assertThat(claimed.lease().epoch()).isEqualTo(1L);
            assertThat(claimed.interaction().getResumeClaimId()).isNotBlank();
            assertThat(claimed.interaction().getResumeClaimEpoch()).isEqualTo(1L);
            assertThat(count(session, "t_agent_run_event", "task_id = 71")).isEqualTo(1);
            assertThat(count(session, "t_agent_run_outbox", "task_id = 71")).isEqualTo(1);
            assertThat(session.getMapper(AgentTaskMapper.class).selectById(71L).getStatus())
                    .isEqualTo("recovering");
            assertThat(session.getMapper(AgentRunInteractionMapper.class)
                    .selectById("question-current").getResumeClaimId())
                    .isEqualTo(claimed.interaction().getResumeClaimId());
        }
    }

    @Test
    void rejectsADuplicateDispatchAfterTheClaimWasConsumedKeepingTheFirstDurableResult() throws Exception {
        DataSource dataSource = dataSource();
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = sessionFactory(dataSource);
        String step = "Resuming after user response";
        String summary = "A persisted user response is ready";
        String idempotencyKey = AgentRunTransitionKey.forInteractionResume(82L, "consumed-question");

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentRunLifecycleService lifecycle = new AgentRunLifecycleService(
                    session.getMapper(AgentTaskMapper.class),
                    session.getMapper(AgentRunEventMapper.class),
                    session.getMapper(AgentRunOutboxMapper.class),
                    session.getMapper(AgentRunInteractionMapper.class));

            AgentRunLifecycleService.InteractionClaimOutcome first = lifecycle.claimResolvedInteractionDispatch(
                    82L, 7, 12, "consumed-question", step, summary, idempotencyKey, "instance-a", 30_000L);
            session.commit();
            assertThat(first.claimed()).isTrue();

            // 执行循环消费 claim 恰好一次。
            AgentRunInteractionService interactions = new AgentRunInteractionService(
                    session.getMapper(AgentRunInteractionMapper.class));
            assertThat(interactions.markDispatchClaimConsumed(
                    "consumed-question", first.interaction().getResumeClaimId())).isTrue();

            // 相同确定性 key 的重复 dispatch 必须 REJECTED，且不得产生第二个事件/outbox。
            AgentRunLifecycleService.InteractionClaimOutcome second = lifecycle.claimResolvedInteractionDispatch(
                    82L, 7, 12, "consumed-question", step, summary, idempotencyKey, "instance-a", 30_000L);
            session.commit();

            assertThat(second.claimed()).isFalse();
            assertThat(second.outcome())
                    .isEqualTo(AgentRunLifecycleService.InteractionClaimOutcome.Outcome.REJECTED);
            assertThat(count(session, "t_agent_run_event", "task_id = 82")).isEqualTo(1);
            assertThat(count(session, "t_agent_run_outbox", "task_id = 82")).isEqualTo(1);
            assertThat(session.getMapper(AgentTaskMapper.class).selectById(82L).getStatus())
                    .isEqualTo("recovering");
            AgentRunInteraction persisted = session.getMapper(AgentRunInteractionMapper.class)
                    .selectById("consumed-question");
            assertThat(persisted.getResumeClaimId()).isEqualTo(first.interaction().getResumeClaimId());
            assertThat(persisted.getResumeConsumedAt()).isNotNull();
        }
    }

    private SqlSessionFactory sessionFactory(DataSource dataSource) {
        com.baomidou.mybatisplus.core.MybatisConfiguration configuration =
                new com.baomidou.mybatisplus.core.MybatisConfiguration(
                        new Environment("interaction-claim-lease", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMapper.class);
        configuration.addMapper(AgentRunEventMapper.class);
        configuration.addMapper(AgentRunOutboxMapper.class);
        configuration.addMapper(AgentRunInteractionMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:interaction_claim_lease_" + UUID.randomUUID()
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource.setUser("sa");
        return dataSource;
    }

    private int count(SqlSession session, String table, String predicate) throws Exception {
        try (Statement statement = session.getConnection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            rows.next();
            return rows.getInt(1);
        }
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
                        origin_message_id BIGINT,
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
            LocalDateTime now = LocalDateTime.now();
            statement.execute("INSERT INTO t_agent_task "
                    + "(task_id, student_id, project_id, status, run_version, last_event_sequence, execution_epoch, "
                    + "execution_owner, execution_lease_expires_at, update_time) VALUES (71, 7, 12, 'waiting_user', "
                    + "0, 0, 0, 'instance-old', TIMESTAMP '" + now.plusMinutes(5).toString().replace('T', ' ')
                    + "', TIMESTAMP '2026-08-02 10:00:00')");
            statement.execute("INSERT INTO t_agent_task "
                    + "(task_id, student_id, project_id, status, run_version, last_event_sequence, execution_epoch, "
                    + "update_time) VALUES (82, 7, 12, 'waiting_user', 0, 0, 0, "
                    + "TIMESTAMP '2026-08-02 10:00:00')");
            statement.execute("INSERT INTO t_agent_run_interaction "
                    + "(interaction_id, task_id, student_id, project_id, interaction_type, status, idempotency_key, "
                    + "create_time, update_time) VALUES ('question-current', 71, 7, 12, 'question', "
                    + "'answered', 'key-question-current', TIMESTAMP '2026-08-02 10:01:00', "
                    + "TIMESTAMP '2026-08-02 10:01:00')");
            statement.execute("INSERT INTO t_agent_run_interaction "
                    + "(interaction_id, task_id, student_id, project_id, interaction_type, status, idempotency_key, "
                    + "create_time, update_time) VALUES ('consumed-question', 82, 7, 12, 'question', "
                    + "'answered', 'key-consumed-question', TIMESTAMP '2026-08-02 10:02:00', "
                    + "TIMESTAMP '2026-08-02 10:02:00')");
        }
    }

    private AgentRunExecutionLeaseService.ExecutionLease lease() {
        return new AgentRunExecutionLeaseService.ExecutionLease(
                71L, "instance-a", 4L, LocalDateTime.of(2026, 7, 23, 10, 1));
    }

    private AgentTask task(String status) {
        AgentTask task = new AgentTask();
        task.setTaskId(71L); task.setStudentId(7); task.setProjectId(12);
        task.setConversationId("conversation-1"); task.setSessionId("session-1");
        task.setMode("agent"); task.setStatus(status);
        return task;
    }

    private AgentRunInteraction interaction() {
        AgentRunInteraction interaction = new AgentRunInteraction();
        interaction.setInteractionId("interaction-71");
        interaction.setTaskId(71L); interaction.setStudentId(7); interaction.setProjectId(12);
        interaction.setInteractionType("question"); interaction.setStatus("answered");
        interaction.setRequestPayload("{\"question\":\"Continue?\"}");
        interaction.setResponsePayload("{\"answer\":\"yes\"}");
        return interaction;
    }
}
