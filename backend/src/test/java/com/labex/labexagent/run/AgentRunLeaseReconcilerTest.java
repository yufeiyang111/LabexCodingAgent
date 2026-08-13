package com.labex.labexagent.run;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunOutbox;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunOutboxMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.service.StudentProjectService;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
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
 * 过期租约必须由周期 reconciler 回收，不能只在 ApplicationReadyEvent 扫描一次。
 * 周期 pass 只回收非终态且没有活动租约的任务：活 owner 不被触碰，等待态保留等待原因，
 * 交互未解决的任务绝不入队，搁浅的 recovering worker 必须可恢复。
 */
class AgentRunLeaseReconcilerTest {

    @Test
    void expiredLeasesAreReclaimedByThePeriodicLeasePass() {
        AgentTask stale = task();
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectExpiredLeaseCandidates(any(), anyInt())).thenReturn(List.of(stale));
        when(tasks.selectById(71L)).thenReturn(stale);
        when(tasks.selectByTaskIdForUpdate(71L)).thenReturn(stale);
        when(tasks.update(isNull(), any())).thenReturn(1);
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        when(events.selectOne(any())).thenReturn(null);
        when(events.selectMaxSequenceByTaskId(71L)).thenReturn(0L);
        doAnswer(invocation -> {
            AgentRunEvent event = invocation.getArgument(0);
            event.setEventId(1L);
            return 1;
        }).when(events).insert(any(AgentRunEvent.class));
        AgentRunOutboxMapper outbox = mock(AgentRunOutboxMapper.class);
        when(outbox.insert(any(AgentRunOutbox.class))).thenReturn(1);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-new", 30_000L);
        AgentRunLifecycleService lifecycle = new AgentRunLifecycleService(tasks, events, outbox);
        StudentProjectService projects = mock(StudentProjectService.class);
        when(projects.getOwnedProject(7, 12)).thenReturn(new StudentProject());
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunTakeoverScheduler takeover = new AgentRunTakeoverScheduler(leases, lifecycle, engine, projects);
        AgentRunRecoveryService recovery =
                new AgentRunRecoveryService(tasks, lifecycle, leases, takeover, null, null, null);

        // SECURE TARGET: 周期 lease pass（post-startup 定时 pass，而非 ApplicationReadyEvent 一次性扫描）
        // 必须回收过期租约：领取新 epoch 并恰好入队一次
        int reclaimed = recovery.reconcileExpiredExecutionLeasesScheduled();

        assertThat(reclaimed).isEqualTo(1);
        verify(engine, times(1)).resume(eq(7), eq(12), any(), eq(71L), eq(true),
                any(AgentRunExecutionLeaseService.ExecutionLease.class));
        ArgumentCaptor<UpdateWrapper<AgentTask>> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(tasks).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet()).contains("execution_epoch");
        assertThat(update.getValue().getParamNameValuePairs().values()).contains("recovering", 5L);
    }

    @Test
    void aConcurrentSchedulerClaimSuppressesTheSecondEnqueue() {
        AgentTask stale = task();
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectExpiredLeaseCandidates(any(), anyInt())).thenReturn(List.of(stale));
        when(tasks.selectById(71L)).thenReturn(stale);
        when(tasks.selectByTaskIdForUpdate(71L)).thenReturn(stale);
        // 第一个实例 CAS 成功，第二个实例在同一 epoch 上 CAS 失败（0 行更新）
        when(tasks.update(isNull(), any())).thenReturn(1, 0);
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        when(events.selectOne(any())).thenReturn(null);
        when(events.selectMaxSequenceByTaskId(71L)).thenReturn(0L);
        doAnswer(invocation -> {
            AgentRunEvent event = invocation.getArgument(0);
            event.setEventId(1L);
            return 1;
        }).when(events).insert(any(AgentRunEvent.class));
        AgentRunOutboxMapper outbox = mock(AgentRunOutboxMapper.class);
        when(outbox.insert(any(AgentRunOutbox.class))).thenReturn(1);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-new", 30_000L);
        AgentRunLifecycleService lifecycle = new AgentRunLifecycleService(tasks, events, outbox);
        StudentProjectService projects = mock(StudentProjectService.class);
        when(projects.getOwnedProject(7, 12)).thenReturn(new StudentProject());
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunTakeoverScheduler takeover = new AgentRunTakeoverScheduler(leases, lifecycle, engine, projects);
        AgentRunRecoveryService recovery =
                new AgentRunRecoveryService(tasks, lifecycle, leases, takeover, null, null, null);

        assertThat(recovery.reconcileExpiredExecutionLeases()).isEqualTo(1);
        assertThat(recovery.reconcileExpiredExecutionLeases()).isEqualTo(0);

        verify(engine, times(1)).resume(eq(7), eq(12), any(), eq(71L), eq(true),
                any(AgentRunExecutionLeaseService.ExecutionLease.class));
    }

    @Test
    void anActiveLeaseIsNeverReclaimedByThePeriodicPass() {
        AgentTask active = task();
        active.setExecutionLeaseExpiresAt(LocalDateTime.now().plusSeconds(30));
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectExpiredLeaseCandidates(any(), anyInt())).thenReturn(List.of(active));
        when(tasks.selectById(71L)).thenReturn(active);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunTakeoverScheduler takeover = mock(AgentRunTakeoverScheduler.class);
        AgentRunRecoveryService recovery = new AgentRunRecoveryService(tasks, lifecycle,
                new AgentRunExecutionLeaseService(tasks, "instance-new", 30_000L), takeover, null, null, null);

        int reclaimed = recovery.reconcileExpiredExecutionLeases();

        assertThat(reclaimed).isEqualTo(0);
        verify(takeover, never()).takeover(any(AgentTask.class));
        verify(lifecycle, never()).appendEventIfCurrent(anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    void aStrandedRecoveringRunIsReclaimedAndDispatchedExactlyOnce() {
        AgentTask stranded = task();
        stranded.setStatus("recovering");
        // 批量查询的快照滞后（epoch 4），reclaim 前重新读取的权威行已是 epoch 6：
        // 幂等键与 claim 分配的 epoch 必须跟随权威行（7），而不是旧快照（5）。
        AgentTask fresh = task();
        fresh.setStatus("recovering");
        fresh.setExecutionEpoch(6L);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectExpiredLeaseCandidates(any(), anyInt())).thenReturn(List.of(stranded));
        when(tasks.selectById(71L)).thenReturn(fresh);
        when(tasks.selectByTaskIdForUpdate(71L)).thenReturn(fresh);
        when(tasks.update(isNull(), any())).thenReturn(1);
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        when(events.selectOne(any())).thenReturn(null);
        when(events.selectMaxSequenceByTaskId(71L)).thenReturn(0L);
        doAnswer(invocation -> {
            AgentRunEvent event = invocation.getArgument(0);
            event.setEventId(1L);
            return 1;
        }).when(events).insert(any(AgentRunEvent.class));
        AgentRunOutboxMapper outbox = mock(AgentRunOutboxMapper.class);
        when(outbox.insert(any(AgentRunOutbox.class))).thenReturn(1);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-new", 30_000L);
        AgentRunLifecycleService lifecycle = new AgentRunLifecycleService(tasks, events, outbox);
        StudentProjectService projects = mock(StudentProjectService.class);
        when(projects.getOwnedProject(7, 12)).thenReturn(new StudentProject());
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunTakeoverScheduler takeover = new AgentRunTakeoverScheduler(leases, lifecycle, engine, projects);
        AgentRunRecoveryService recovery =
                new AgentRunRecoveryService(tasks, lifecycle, leases, takeover, null, null, null);

        int reclaimed = recovery.reconcileExpiredExecutionLeases();

        assertThat(reclaimed).isEqualTo(1);
        verify(engine, times(1)).resume(eq(7), eq(12), any(), eq(71L), eq(true),
                any(AgentRunExecutionLeaseService.ExecutionLease.class));
        ArgumentCaptor<UpdateWrapper<AgentTask>> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(tasks).update(isNull(), update.capture());
        assertThat(update.getValue().getParamNameValuePairs().values()).contains("preparing", 7L);
    }

    @Test
    void anUnresolvedInteractionIsNeverEnqueuedByThePeriodicPass() {
        AgentTask waiting = task();
        waiting.setStatus("waiting_approval");
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectExpiredLeaseCandidates(any(), anyInt())).thenReturn(List.of(waiting));
        when(tasks.selectById(71L)).thenReturn(waiting);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        when(lifecycle.appendEventIfCurrent(
                eq(71L), eq(AgentRunState.WAITING_APPROVAL), eq("RUN_RECOVERY_WAITING"), any(), anyString()))
                .thenReturn(new AgentRunEvent());
        AgentRunTakeoverScheduler takeover = mock(AgentRunTakeoverScheduler.class);
        AgentRunRecoveryService recovery = new AgentRunRecoveryService(tasks, lifecycle,
                new AgentRunExecutionLeaseService(tasks, "instance-new", 30_000L), takeover, null, null, null);

        int reclaimed = recovery.reconcileExpiredExecutionLeases();

        assertThat(reclaimed).isEqualTo(1);
        verify(lifecycle).appendEventIfCurrent(eq(71L), eq(AgentRunState.WAITING_APPROVAL),
                eq("RUN_RECOVERY_WAITING"), any(), eq("recovery-71-waiting"));
        verify(takeover, never()).takeover(any(AgentTask.class));
        verify(lifecycle, never()).transition(eq(71L), eq(AgentRunState.COMPLETED), any(), any(), any(), any(), any());
    }

    @Test
    void aTerminalTaskIsNeverTouchedByThePeriodicPass() {
        AgentTask done = task();
        done.setStatus("completed");
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectExpiredLeaseCandidates(any(), anyInt())).thenReturn(List.of(done));
        when(tasks.selectById(71L)).thenReturn(done);
        AgentRunLifecycleService lifecycle = mock(AgentRunLifecycleService.class);
        AgentRunTakeoverScheduler takeover = mock(AgentRunTakeoverScheduler.class);
        AgentRunRecoveryService recovery = new AgentRunRecoveryService(tasks, lifecycle,
                new AgentRunExecutionLeaseService(tasks, "instance-new", 30_000L), takeover, null, null, null);

        int reclaimed = recovery.reconcileExpiredExecutionLeases();

        assertThat(reclaimed).isEqualTo(0);
        verify(takeover, never()).takeover(any(AgentTask.class));
        verify(lifecycle, never()).appendEventIfCurrent(anyLong(), any(), anyString(), any(), anyString());
    }

    @Test
    void thePeriodicPassUsesTheConfiguredBoundedBatchSizeWithAConservativeDefault() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectExpiredLeaseCandidates(any(), anyInt())).thenReturn(List.of());
        AgentRunRecoveryService recovery = new AgentRunRecoveryService(tasks,
                mock(AgentRunLifecycleService.class), mock(AgentRunExecutionLeaseService.class),
                mock(AgentRunTakeoverScheduler.class), null, null, null);

        recovery.reconcileExpiredExecutionLeases();
        verify(tasks).selectExpiredLeaseCandidates(any(), eq(50));

        recovery.setLeaseReconciliationBatchSize(7);
        recovery.reconcileExpiredExecutionLeases();
        verify(tasks).selectExpiredLeaseCandidates(any(), eq(7));
    }

    @Test
    void recoveryScanReclaimsALeaseThatExpiredAfterStartup() {
        AgentTask stale = task();
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectList(any())).thenReturn(List.of(stale));
        when(tasks.selectByTaskIdForUpdate(71L)).thenReturn(stale);
        when(tasks.update(isNull(), any())).thenReturn(1);
        AgentRunEventMapper events = mock(AgentRunEventMapper.class);
        when(events.selectOne(any())).thenReturn(null);
        when(events.selectMaxSequenceByTaskId(71L)).thenReturn(0L);
        doAnswer(invocation -> {
            AgentRunEvent event = invocation.getArgument(0);
            event.setEventId(1L);
            return 1;
        }).when(events).insert(any(AgentRunEvent.class));
        AgentRunOutboxMapper outbox = mock(AgentRunOutboxMapper.class);
        when(outbox.insert(any(AgentRunOutbox.class))).thenReturn(1);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-new", 30_000L);
        AgentRunLifecycleService lifecycle = new AgentRunLifecycleService(tasks, events, outbox);
        StudentProjectService projects = mock(StudentProjectService.class);
        when(projects.getOwnedProject(7, 12)).thenReturn(new StudentProject());
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentRunTakeoverScheduler takeover = new AgentRunTakeoverScheduler(leases, lifecycle, engine, projects);
        AgentRunRecoveryService recovery =
                new AgentRunRecoveryService(tasks, lifecycle, leases, takeover, null, null, null);

        int recovered = recovery.recoverInterruptedRuns();

        ArgumentCaptor<UpdateWrapper<AgentTask>> update = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(tasks).update(isNull(), update.capture());
        assertThat(recovered).isEqualTo(1);
        assertThat(update.getValue().getSqlSet()).contains("execution_epoch");
        assertThat(update.getValue().getParamNameValuePairs().values()).contains("recovering", 5L);
    }

    @Test
    void acquireReclaimsAnExpiredLeaseForTheNextWorker() {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        when(tasks.selectById(71L)).thenReturn(task());
        when(tasks.update(isNull(), any())).thenReturn(1);
        AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(tasks, "instance-new", 30_000L);

        AgentRunExecutionLeaseService.ExecutionLease claim =
                leases.acquire(71L, LocalDateTime.of(2026, 7, 23, 10, 1));

        assertThat(claim).isNotNull();
        assertThat(claim.owner()).isEqualTo("instance-new");
        assertThat(claim.epoch()).isEqualTo(5L);
    }

    @Test
    void selectExpiredLeaseCandidatesReturnsOnlyExpiredNonTerminalLeases() throws Exception {
        DataSource dataSource = dataSource(UUID.randomUUID().toString());
        createLeaseSchemaAndFixtures(dataSource);
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            List<AgentTask> candidates = session.getMapper(AgentTaskMapper.class)
                    .selectExpiredLeaseCandidates(LocalDateTime.of(2026, 8, 2, 12, 0), 100);

            // 过期租约 -> 候选；活动租约 -> 排除；NULL owner/expires -> 候选；
            // 终态 -> 排除；waiting_approval/waiting_user -> 排除；
            // 空字符串 owner 在租约未过期时与 claim CAS 一致地被排除，租约过期后才成为候选。
            assertThat(candidates)
                    .extracting(AgentTask::getTaskId)
                    .containsExactly(101L, 103L, 108L, 109L, 110L, 113L, 114L);
        }
    }

    @Test
    void selectExpiredLeaseCandidatesIsBoundedByTheBatchSize() throws Exception {
        DataSource dataSource = dataSource(UUID.randomUUID().toString());
        createLeaseSchema(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            for (long taskId = 201L; taskId <= 205L; taskId++) {
                insertLeaseTask(statement, taskId, "running", "instance-a", "2026-08-02 11:00:00");
            }
        }
        SqlSessionFactory sessionFactory = factory(dataSource);

        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentTaskMapper mapper = session.getMapper(AgentTaskMapper.class);
            assertThat(mapper.selectExpiredLeaseCandidates(LocalDateTime.of(2026, 8, 2, 12, 0), 3))
                    .extracting(AgentTask::getTaskId)
                    .containsExactly(201L, 202L, 203L);
            assertThat(mapper.selectExpiredLeaseCandidates(LocalDateTime.of(2026, 8, 2, 12, 0), 10))
                    .extracting(AgentTask::getTaskId)
                    .containsExactly(201L, 202L, 203L, 204L, 205L);
        }
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("lease-reconciler", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource(String dbSuffix) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:lease_reconciler_" + dbSuffix
                + ";MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000");
        dataSource.setUser("sa");
        return dataSource;
    }

    private void createLeaseSchemaAndFixtures(DataSource dataSource) throws Exception {
        createLeaseSchema(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            insertLeaseTask(statement, 101L, "running", "instance-a", "2026-08-02 11:00:00");
            insertLeaseTask(statement, 102L, "running", "instance-b", "2026-08-02 13:00:00");
            insertLeaseTask(statement, 103L, "queued", null, null);
            insertLeaseTask(statement, 104L, "completed", "instance-c", "2026-08-02 11:00:00");
            insertLeaseTask(statement, 105L, "waiting_approval", "instance-d", "2026-08-02 11:00:00");
            insertLeaseTask(statement, 106L, "waiting_user", "instance-e", "2026-08-02 11:00:00");
            insertLeaseTask(statement, 107L, "preparing", "", "2026-08-02 13:00:00");
            insertLeaseTask(statement, 108L, "preparing", "", "2026-08-02 11:00:00");
            insertLeaseTask(statement, 109L, "waiting_environment", "instance-f", "2026-08-02 11:00:00");
            insertLeaseTask(statement, 110L, "waiting_workspace", "instance-g", "2026-08-02 11:00:00");
            insertLeaseTask(statement, 111L, "cancelled", "instance-h", "2026-08-02 11:00:00");
            insertLeaseTask(statement, 112L, "failed", "instance-i", "2026-08-02 11:00:00");
            insertLeaseTask(statement, 113L, "running", null, "2026-08-02 13:00:00");
            insertLeaseTask(statement, 114L, "queued", "", "2026-08-02 11:00:00");
        }
    }

    private void createLeaseSchema(DataSource dataSource) throws Exception {
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
        }
    }

    private void insertLeaseTask(Statement statement, long taskId, String status, String owner, String expiresAt)
            throws Exception {
        String ownerValue = owner == null ? "NULL" : "'" + owner + "'";
        String expiry = expiresAt == null ? "NULL" : "TIMESTAMP '" + expiresAt + "'";
        statement.execute("INSERT INTO t_agent_task "
                + "(task_id, student_id, project_id, status, run_version, last_event_sequence, execution_epoch, "
                + "execution_owner, execution_lease_expires_at, update_time) VALUES ("
                + taskId + ", 7, 12, '" + status + "', 0, 0, 0, " + ownerValue + ", " + expiry
                + ", TIMESTAMP '2026-08-02 10:00:00')");
    }

    private AgentTask task() {
        AgentTask task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setConversationId("conversation-1");
        task.setSessionId("session-1");
        task.setMode("agent");
        task.setStatus("running");
        task.setExecutionOwner("instance-b");
        task.setExecutionEpoch(4L);
        task.setExecutionLeaseExpiresAt(LocalDateTime.of(2026, 7, 23, 9, 59));
        task.setExecutionHeartbeatAt(LocalDateTime.of(2026, 7, 23, 9, 58));
        return task;
    }
}
