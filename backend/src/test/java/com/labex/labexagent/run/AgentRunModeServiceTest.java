package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentTask;
import com.labex.labexagent.runtime.AgentContext;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunOutboxMapper;
import com.labex.mapper.AgentTaskMapper;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * plan -> build 模式切换必须是 fenced、durable、幂等的：
 * CAS 更新 task.mode、持久化 RUN_MODE_CHANGED 之后才更新内存 context，
 * 任何 fence/状态失败都不能产生部分持久化或静默 fallback。
 */
class AgentRunModeServiceTest {

    @Test
    void persistsBuildModeAndEmitsRunModeChangedOnlyAfterTheFencedCas() {
        AgentTask task = task("plan");
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        when(tasks.update(any(), any())).thenReturn(1);
        when(tasks.selectById(71L)).thenReturn(task);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        when(lifecycle.hasEvent(71L, AgentRunModeService.planToBuildKey(71L))).thenReturn(false);
        AgentRunEvent event = new AgentRunEvent();
        event.setEventId(901L);
        event.setEventType("RUN_MODE_CHANGED");
        when(lifecycle.appendEvent(any(ExecutionFence.class), org.mockito.ArgumentMatchers.eq(71L),
                org.mockito.ArgumentMatchers.eq("RUN_MODE_CHANGED"), any(),
                org.mockito.ArgumentMatchers.eq(AgentRunModeService.planToBuildKey(71L)))).thenReturn(event);
        AgentContext context = context("plan");
        AgentRunModeService service = modeService(tasks, lifecycle);

        AgentRunEvent result = service.transitionPlanToBuild(
                71L, fence(), AgentRunModeService.planToBuildKey(71L), context);

        assertThat(result).isSameAs(event);
        // durable 实体与内存 context 只在 CAS + 事件成功后更新
        assertThat(task.getMode()).isEqualTo("build");
        assertThat(context.getMode()).isEqualTo("build");
        ArgumentCaptor<UpdateWrapper<AgentTask>> wrapper = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(tasks).update(any(), wrapper.capture());
        assertThat(wrapper.getValue().getSqlSet()).contains("mode=");
        assertThat(wrapper.getValue().getParamNameValuePairs()).containsValue("build");
        assertThat(wrapper.getValue().getSqlSegment())
                .contains("mode")
                .contains("execution_owner")
                .contains("execution_epoch")
                .contains("execution_lease_expires_at");
        verify(lifecycle).appendEvent(org.mockito.ArgumentMatchers.eq(fence()),
                org.mockito.ArgumentMatchers.eq(71L),
                org.mockito.ArgumentMatchers.eq("RUN_MODE_CHANGED"), any(),
                org.mockito.ArgumentMatchers.eq(AgentRunModeService.planToBuildKey(71L)));
    }

    @Test
    void isIdempotentOnReplayOfTheSameIdempotencyKey() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        when(lifecycle.hasEvent(71L, AgentRunModeService.planToBuildKey(71L))).thenReturn(true);
        AgentContext context = context("plan");
        AgentRunModeService service = modeService(tasks, lifecycle);

        AgentRunEvent result = service.transitionPlanToBuild(
                71L, fence(), AgentRunModeService.planToBuildKey(71L), context);

        assertThat(result).isNull();
        assertThat(context.getMode()).isEqualTo("build");
        verify(tasks, never()).update(any(), any());
        verify(lifecycle, never()).appendEvent(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsStaleFenceWithoutAnyDurableWrite() {
        AgentTask task = task("plan");
        task.setExecutionOwner("other-instance");
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(71L)).thenReturn(task);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunModeService service = modeService(tasks, lifecycle);

        assertThatThrownBy(() -> service.transitionPlanToBuild(
                71L, fence(), AgentRunModeService.planToBuildKey(71L), context("plan")))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .hasMessageContaining("stale-owner");
        verify(tasks, never()).update(any(), any());
        verify(lifecycle, never()).appendEvent(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsMissingTaskWithTypedFenceFailure() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(0L);
        when(tasks.selectById(71L)).thenReturn(null);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunModeService service = modeService(tasks, lifecycle);

        assertThatThrownBy(() -> service.transitionPlanToBuild(
                71L, fence(), AgentRunModeService.planToBuildKey(71L), context("plan")))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .hasMessageContaining("task-not-found");
        verify(tasks, never()).update(any(), any());
        verify(lifecycle, never()).appendEvent(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsNullFenceAsInvalidFence() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunModeService service = modeService(tasks, lifecycle);

        assertThatThrownBy(() -> service.transitionPlanToBuild(
                71L, null, AgentRunModeService.planToBuildKey(71L), context("plan")))
                .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class)
                .hasMessageContaining("invalid-fence");
        verify(tasks, never()).update(any(), any());
        verify(lifecycle, never()).appendEvent(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsATaskThatIsNotInPlanModeWithoutEmittingTheEvent() {
        AgentTask task = task("agent");
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        when(tasks.update(any(), any())).thenReturn(0);
        when(tasks.selectById(71L)).thenReturn(task);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        when(lifecycle.hasEvent(71L, AgentRunModeService.planToBuildKey(71L))).thenReturn(false);
        AgentRunModeService service = modeService(tasks, lifecycle);

        assertThatThrownBy(() -> service.transitionPlanToBuild(
                71L, fence(), AgentRunModeService.planToBuildKey(71L), context("plan")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not in plan mode");
        assertThat(task.getMode()).isEqualTo("agent");
        verify(lifecycle, never()).appendEvent(any(), any(), any(), any(), any());
    }

    @Test
    void concurrentDoublePlanExitLoserConvergesIdempotentlyOnTheDurableEvent() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectCount(any())).thenReturn(1L);
        // 落败方 CAS 命中 0 行，但 durable RUN_MODE_CHANGED 事件已由先提交者持久化：
        // 初次幂等检查为 false，CAS 落败后的重放收敛检查为 true。
        when(tasks.update(any(), any())).thenReturn(0);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        when(lifecycle.hasEvent(71L, AgentRunModeService.planToBuildKey(71L)))
                .thenReturn(false)
                .thenReturn(true);
        AgentContext context = context("plan");
        AgentRunModeService service = modeService(tasks, lifecycle);

        AgentRunEvent result = service.transitionPlanToBuild(
                71L, fence(), AgentRunModeService.planToBuildKey(71L), context);

        assertThat(result).isNull();
        assertThat(context.getMode()).isEqualTo("build");
        verify(tasks, never()).selectById(any());
        verify(lifecycle, never()).appendEvent(any(), any(), any(), any(), any());
    }

    @Test
    void persistsTheModeCasAndRunModeChangedEventAgainstTheDatabase() throws Exception {
        DataSource dataSource = dataSource(UUID.randomUUID().toString());
        createSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(false)) {
            AgentTaskMapper taskMapper = session.getMapper(AgentTaskMapper.class);
            AgentRunExecutionLeaseService leases =
                    new AgentRunExecutionLeaseService(taskMapper, "instance-a", 30_000L);
            AgentRunLifecycleService lifecycle = new AgentRunLifecycleService(
                    taskMapper,
                    session.getMapper(AgentRunEventMapper.class),
                    session.getMapper(AgentRunOutboxMapper.class),
                    null,
                    leases);
            AgentRunModeService modeService = new AgentRunModeService(taskMapper, lifecycle, leases);
            AgentContext context = context("plan");
            ExecutionFence fence = new ExecutionFence(71L, "instance-a", 4L);

            AgentRunEvent event = modeService.transitionPlanToBuild(
                    71L, fence, AgentRunModeService.planToBuildKey(71L), context);
            session.commit();

            assertThat(event).isNotNull();
            assertThat(event.getEventType()).isEqualTo("RUN_MODE_CHANGED");
            assertThat(context.getMode()).isEqualTo("build");
            // 重新读取 durable 行：mode 已持久化，model_config_id 未被触碰
            AgentTask reloaded = taskMapper.selectById(71L);
            assertThat(reloaded.getMode()).isEqualTo("build");
            assertThat(reloaded.getStatus()).isEqualTo("running");
            assertThat(reloaded.getModelConfigId()).isEqualTo(42);
            // RUN_MODE_CHANGED 事件行存在，payload 只含安全键集，无秘密字段
            try (Statement statement = session.getConnection().createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT event_type, payload FROM t_agent_run_event WHERE task_id = 71")) {
                assertTrue(rows.next(), "RUN_MODE_CHANGED event row must exist");
                assertThat(rows.getString(1)).isEqualTo("RUN_MODE_CHANGED");
                JsonObject payload = JsonParser.parseString(rows.getString(2)).getAsJsonObject();
                assertThat(payload.keySet()).containsExactlyInAnyOrder(
                        "taskId", "epoch", "previousMode", "nextMode", "idempotencyKey");
                assertThat(payload.get("previousMode").getAsString()).isEqualTo("plan");
                assertThat(payload.get("nextMode").getAsString()).isEqualTo("build");
                assertThat(payload.get("epoch").getAsLong()).isEqualTo(4L);
                assertThat(payload.get("idempotencyKey").getAsString())
                        .isEqualTo(AgentRunModeService.planToBuildKey(71L));
                assertFalse(rows.next(), "exactly one RUN_MODE_CHANGED event row");
            }
            assertThat(count(session, "t_agent_run_outbox", "task_id = 71")).isEqualTo(1);
            // 幂等重放：同键不产生重复事件/outbox
            AgentRunEvent replay = modeService.transitionPlanToBuild(
                    71L, fence, AgentRunModeService.planToBuildKey(71L), context);
            session.commit();
            assertThat(replay).isNull();
            assertThat(count(session, "t_agent_run_event", "task_id = 71")).isEqualTo(1);
            assertThat(count(session, "t_agent_run_outbox", "task_id = 71")).isEqualTo(1);
        }
    }

    private AgentRunModeService modeService(AgentTaskMapper tasks, AgentRunLifecycleService lifecycle) {
        return new AgentRunModeService(tasks, lifecycle,
                new AgentRunExecutionLeaseService(tasks, "instance-a", 30_000L));
    }

    private ExecutionFence fence() {
        return new ExecutionFence(71L, "instance-a", 4L);
    }

    private AgentContext context(String mode) {
        AgentContext context = new AgentContext("session-1", 7, null, "conversation-1", 71L,
                java.nio.file.Path.of("."), new java.util.ArrayList<>(), 0);
        context.setMode(mode);
        return context;
    }

    private AgentTask task(String mode) {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setMode(mode);
        task.setStatus("running");
        task.setExecutionOwner("instance-a");
        task.setExecutionEpoch(4L);
        task.setExecutionLeaseExpiresAt(LocalDateTime.now().plusSeconds(30));
        return task;
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("mode-service", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMapper.class);
        configuration.addMapper(AgentRunEventMapper.class);
        configuration.addMapper(AgentRunOutboxMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource(String dbSuffix) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:mode_service_" + dbSuffix
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
            String expiresAt = LocalDateTime.now().plusSeconds(60)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            statement.execute("INSERT INTO t_agent_task "
                    + "(task_id, student_id, project_id, mode, model_config_id, status, run_version, "
                    + "last_event_sequence, execution_owner, execution_epoch, execution_lease_expires_at, "
                    + "execution_heartbeat_at, update_time) VALUES (71, 7, 12, 'plan', 42, 'running', 0, 0, "
                    + "'instance-a', 4, TIMESTAMP '" + expiresAt + "', TIMESTAMP '" + now
                    + "', TIMESTAMP '" + now + "')");
        }
    }

    private int count(SqlSession session, String table, String predicate) throws Exception {
        try (Statement statement = session.getConnection().createStatement();
             ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table + " WHERE " + predicate)) {
            rows.next();
            return rows.getInt(1);
        }
    }
}
