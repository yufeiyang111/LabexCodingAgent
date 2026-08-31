package com.labex.labexagent.projectconfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.google.gson.JsonParser;
import com.labex.entity.AgentProjectConfigRevision;
import com.labex.entity.AgentRunConfigSnapshot;
import com.labex.entity.AgentTask;
import com.labex.entity.StudentProject;
import com.labex.labexagent.projectconfig.AgentEffectiveProjectConfigService.EffectiveProjectConfig;
import com.labex.labexagent.run.AgentRunConfigurationException;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.mapper.AgentProjectConfigRevisionMapper;
import com.labex.mapper.AgentRunConfigSnapshotMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.service.StudentProjectService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.List;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentRunConfigSnapshotServiceTest {

    @TempDir
    Path tempDir;

    private DataSource dataSource;
    private SqlSessionFactory sessionFactory;
    private AgentRunConfigSnapshotService service;
    private AgentRunExecutionLeaseService leaseService;
    private StudentProject project;
    private AgentTask task;

    @BeforeEach
    void setUp() throws Exception {
        dataSource = dataSource(UUID.randomUUID().toString());
        createTable(dataSource);
        sessionFactory = factory(dataSource);

        Path projectRoot = Files.createDirectories(tempDir.resolve("workspace"));
        Path configDir = Files.createDirectories(projectRoot.resolve(".labex-agent/project"));
        Files.createDirectories(configDir.resolve("agents"));
        Files.writeString(configDir.resolve("agent.json"), """
                {
                  "schemaVersion": 1,
                  "defaultAgent": "agents/agent-a.json",
                  "agents": ["agents/agent-a.json"],
                  "models": ["model-1"],
                  "runtimeProfile": "strict"
                }
                """);
        Files.writeString(configDir.resolve("agents/agent-a.json"),
                "{\"id\": \"agent-a\", \"name\": \"Agent A\"}");

        StudentProjectService studentProjectService = mock(StudentProjectService.class);
        project = new StudentProject();
        project.setStudentId(7);
        project.setProjectId(12);
        project.setWorkspacePath(projectRoot.toString());
        when(studentProjectService.getOwnedProject(7, 12)).thenReturn(project);

        AgentProjectConfigRevisionMapper revisionMapper = mock(AgentProjectConfigRevisionMapper.class);
        when(revisionMapper.selectOne(any())).thenReturn(null);
        when(revisionMapper.insert(any())).thenReturn(1);
        AgentProjectConfigExternalChangeService externalChangeService =
                mock(AgentProjectConfigExternalChangeService.class);
        AgentProjectConfigRevisionService revisionService =
                new AgentProjectConfigRevisionService(studentProjectService, revisionMapper, externalChangeService);
        AgentEffectiveProjectConfigService effectiveService =
                new AgentEffectiveProjectConfigService(revisionService);

        leaseService = mock(AgentRunExecutionLeaseService.class);
        service = new AgentRunConfigSnapshotService(
                sessionFactory.openSession(true).getMapper(AgentRunConfigSnapshotMapper.class),
                effectiveService, leaseService);

        task = new AgentTask();
        task.setTaskId(71L);
        task.setStudentId(7);
        task.setProjectId(12);
        task.setMode("plan");
        task.setModelConfigId(42);
    }

    @Test
    void createsThePreQueueSnapshotAtEpochZeroBeforeTheTaskIsQueued() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            AgentRunConfigSnapshot created = service.createForNewTask(7, project, 71L, 42, "plan");
            assertThat(created.getExecutionEpoch()).isEqualTo(0L);
            assertThat(created.getTaskId()).isEqualTo(71L);

            AgentRunConfigSnapshot reloaded = service.getForEpoch(71L, 0L);
            assertThat(reloaded).isNotNull();
            assertThat(reloaded.getProjectConfigRevision()).isEqualTo(1L);
            assertThat(reloaded.getEffectiveConfigJson()).contains("\"modelConfigId\":42");
            assertThat(reloaded.getEffectiveConfigDigest()).isNotBlank();
            assertThat(reloaded.getSecretAliasesJson()).doesNotContain("sk-");
            assertThat(session.getMapper(AgentRunConfigSnapshotMapper.class)
                    .selectList(null)).hasSize(1);
        }
    }

    @Test
    void replacesProviderFallbackEvidenceWithinTheSameIteration() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            service.createForNewTask(7, project, 71L, 42, "plan");
            ExecutionFence fence = new ExecutionFence(71L, "instance-a", 0L);
            var first = new com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.RequestEvidence(
                    List.of(), List.of(), List.of(), "medium", "medium", "shape-a", "request-a",
                    List.of(new com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.MessageFingerprint(
                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 12)), 12, 0, false);
            var fallback = new com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.RequestEvidence(
                    List.of(), List.of(), List.of(), "medium", "medium", "shape-b", "request-b",
                    List.of(new com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.MessageFingerprint(
                            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 13)), 13, 0, false);
            var next = new com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.RequestEvidence(
                    List.of(), List.of(), List.of(), "medium", "medium", "shape-b", "request-c",
                    List.of(
                            new com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.MessageFingerprint(
                                    "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 13),
                            new com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.MessageFingerprint(
                                    "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc", 14)), 27, 0, false);

            service.appendRequestEvidence(fence, 1, first);
            service.appendRequestEvidence(fence, 1, fallback);
            service.appendRequestEvidence(fence, 2, next);

            var entries = JsonParser.parseString(service.getForEpoch(71L, 0L).getRequestEvidenceJson())
                    .getAsJsonArray();
            assertThat(entries).hasSize(2);
            assertThat(entries.get(0).getAsJsonObject().get("requestShapeDigest").getAsString())
                    .isEqualTo("shape-b");
            assertThat(entries.get(1).getAsJsonObject().get("prefixState").getAsString())
                    .isEqualTo("stable");
        }
    }

    @Test
    void carriesARejectedPromptCacheCapabilityAcrossExecutionEpochs() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            service.createForNewTask(7, project, 71L, 42, "plan");
            ExecutionFence initialFence = new ExecutionFence(71L, "instance-a", 0L);
            service.appendRequestEvidence(initialFence, 1,
                    new com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.RequestEvidence(
                            List.of(), List.of(), List.of(), "medium", "medium", "shape", "request",
                            List.of(), 0, 0, true));

            assertThat(service.promptCacheKeyRejected(71L, 0L)).isTrue();

            service.copyForNewEpoch(71L, 3L, new ExecutionFence(71L, "instance-a", 3L));

            assertThat(service.promptCacheKeyRejected(71L, 3L)).isTrue();
        }
    }

    @Test
    void recordsAppendOnlyPrefixEvidenceWithoutPersistingMessageContent() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            service.createForNewTask(7, project, 71L, 42, "plan");
            ExecutionFence fence = new ExecutionFence(71L, "instance-a", 0L);

            service.appendRequestEvidence(fence, 1,
                    new com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.RequestEvidence(
                            List.of(), List.of(), List.of(), "medium", "medium", "shape-a", "request-a",
                            List.of(new com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.MessageFingerprint(
                                    "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 12)), 12, 0, false));
            service.appendRequestEvidence(fence, 2,
                    new com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.RequestEvidence(
                            List.of(), List.of(), List.of(), "medium", "medium", "shape-a", "request-b",
                            List.of(
                                    new com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.MessageFingerprint(
                                            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", 12),
                                    new com.labex.labexagent.llm.OpenAiCompatibleChatRequestAdapter.MessageFingerprint(
                                            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", 14)),
                            26, 0, false));

            String evidence = service.getForEpoch(71L, 0L).getRequestEvidenceJson();
            var entries = JsonParser.parseString(evidence).getAsJsonArray();
            assertThat(entries.get(0).getAsJsonObject().get("prefixState").getAsString())
                    .isEqualTo("baseline");
            assertThat(entries.get(1).getAsJsonObject().get("prefixStable").getAsBoolean()).isTrue();
            assertThat(entries.get(1).getAsJsonObject().get("commonPrefixMessages").getAsInt())
                    .isEqualTo(1);
            assertThat(entries.get(1).getAsJsonObject().get("prefixResetReason").getAsString())
                    .isEqualTo("append_only");
            assertThat(entries.get(0).getAsJsonObject().has("messageFingerprints")).isFalse();
            assertThat(evidence).doesNotContain("original objective");

            AgentRunConfigSnapshotService.PrefixTelemetry telemetry =
                    service.latestPrefixTelemetry(71L, 0L, 2);
            assertThat(telemetry.state()).isEqualTo("stable");
            assertThat(telemetry.reported()).isTrue();
            assertThat(telemetry.stable()).isTrue();
            assertThat(telemetry.commonPrefixMessages()).isEqualTo(1);
            assertThat(telemetry.resetReason()).isEqualTo("append_only");
            assertThat(service.latestPrefixTelemetry(71L, 0L, 1).reported()).isFalse();
        }
    }

    @Test
    void copiesTheSameEffectiveRevisionIntoANewEpochWithoutMutatingThePriorRow() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            service.createForNewTask(7, project, 71L, 42, "plan");

            ExecutionFence fence = new ExecutionFence(71L, "instance-a", 3L);
            AgentRunConfigSnapshot copy = service.copyForNewEpoch(71L, 3L, fence);

            assertThat(copy.getExecutionEpoch()).isEqualTo(3L);
            assertThat(copy.getEffectiveConfigDigest())
                    .isEqualTo(service.getForEpoch(71L, 0L).getEffectiveConfigDigest());
            assertThat(service.getForEpoch(71L, 0L).getEffectiveConfigJson()).isNotNull();
            assertThat(session.getMapper(AgentRunConfigSnapshotMapper.class)
                    .selectList(null)).hasSize(2);
        }
    }

    @Test
    void rejectsTheEpochCopyWhenTheFenceIsStaleWithoutWriting() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            service.createForNewTask(7, project, 71L, 42, "plan");
            doThrow(new AgentRunExecutionLeaseService.StaleExecutionFenceException(
                            AgentRunExecutionLeaseService.StaleExecutionFenceException.Reason.EXPIRED_LEASE))
                    .when(leaseService).requireActiveFenceForWrite(any(), any());

            ExecutionFence fence = new ExecutionFence(71L, "instance-a", 3L);
            assertThatThrownBy(() -> service.copyForNewEpoch(71L, 3L, fence))
                    .isInstanceOf(AgentRunExecutionLeaseService.StaleExecutionFenceException.class);
            assertThat(session.getMapper(AgentRunConfigSnapshotMapper.class)
                    .selectList(null)).hasSize(1);
        }
    }

    @Test
    void createsAMigrationSnapshotFromTheExactPersistedModelReference() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            ExecutionFence fence = new ExecutionFence(71L, "instance-a", 1L);

            AgentRunConfigSnapshot snapshot = service.createMigrationSnapshot(task, project, fence);

            assertThat(snapshot.getExecutionEpoch()).isEqualTo(1L);
            assertThat(snapshot.getEffectiveConfigJson()).contains("\"modelConfigId\":42");
            assertThat(service.getLatest(71L).getExecutionEpoch()).isEqualTo(1L);
        }
    }

    @Test
    void failsClosedWhenTheLegacyTaskHasNoPersistedModelReference() {
        try (SqlSession session = sessionFactory.openSession(true)) {
            ExecutionFence fence = new ExecutionFence(71L, "instance-a", 1L);
            task.setModelConfigId(null);

            assertThatThrownBy(() -> service.createMigrationSnapshot(task, project, fence))
                    .isInstanceOf(AgentRunConfigurationException.class);
            assertThat(session.getMapper(AgentRunConfigSnapshotMapper.class)
                    .selectList(null)).isEmpty();
        }
    }

    @Test
    void failsClosedWhenTheMigrationSnapshotReferencesUnresolvableConfiguration() throws Exception {
        Files.writeString(tempDir.resolve("workspace/.labex-agent/project/agent.json"),
                "{\"schemaVersion\": 1}");

        try (SqlSession session = sessionFactory.openSession(true)) {
            ExecutionFence fence = new ExecutionFence(71L, "instance-a", 1L);

            assertThatThrownBy(() -> service.createMigrationSnapshot(task, project, fence))
                    .isInstanceOf(AgentEffectiveProjectConfigService.EffectiveConfigException.class);
            assertThat(session.getMapper(AgentRunConfigSnapshotMapper.class)
                    .selectList(null)).isEmpty();
        }
    }

    @Test
    void aggregatesHistoricalPrefixEvidenceWithoutCreatingAnotherFactSource() {
        AgentRunConfigSnapshotMapper snapshots = mock(AgentRunConfigSnapshotMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentTask durableTask = new AgentTask();
        durableTask.setTaskId(71L);
        durableTask.setConversationId("conversation-7");
        AgentRunConfigSnapshot snapshot = new AgentRunConfigSnapshot();
        snapshot.setTaskId(71L);
        snapshot.setExecutionEpoch(0L);
        snapshot.setRequestEvidenceJson("""
                [{"iteration":1,"prefixState":"baseline"},
                 {"iteration":2,"prefixState":"stable","prefixStable":true,"prefixResetReason":"append_only"},
                 {"iteration":3,"prefixState":"reset","prefixStable":false,"prefixResetReason":"static_prefix_changed"}]
                """);
        when(tasks.selectList(any())).thenReturn(List.of(durableTask));
        when(snapshots.selectList(any())).thenReturn(List.of(snapshot));

        AgentRunConfigSnapshotService aggregateService = new AgentRunConfigSnapshotService(
                snapshots, mock(AgentEffectiveProjectConfigService.class),
                mock(AgentRunExecutionLeaseService.class), tasks);

        AgentRunConfigSnapshotService.PrefixAggregate aggregate =
                aggregateService.prefixStatsForConversation("conversation-7");

        assertThat(aggregate.reportedCalls()).isEqualTo(2);
        assertThat(aggregate.stableCalls()).isEqualTo(1);
        assertThat(aggregate.resetCalls()).isEqualTo(1);
        assertThat(aggregate.stabilityRate()).isEqualTo(50.0);
        assertThat(aggregate.state()).isEqualTo("reset");
        assertThat(aggregate.toPayload()).containsEntry("prefixReportedCalls", 2);
    }

    @Test
    void latestBaselineFromANewerTaskDoesNotReuseAnOlderStableState() {
        AgentRunConfigSnapshotMapper snapshots = mock(AgentRunConfigSnapshotMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentTask olderTask = new AgentTask();
        olderTask.setTaskId(71L);
        olderTask.setConversationId("conversation-7");
        AgentTask newerTask = new AgentTask();
        newerTask.setTaskId(72L);
        newerTask.setConversationId("conversation-7");
        AgentRunConfigSnapshot older = new AgentRunConfigSnapshot();
        older.setTaskId(71L);
        older.setExecutionEpoch(0L);
        older.setRequestEvidenceJson("[{\"prefixState\":\"stable\",\"prefixStable\":true}]");
        AgentRunConfigSnapshot newer = new AgentRunConfigSnapshot();
        newer.setTaskId(72L);
        newer.setExecutionEpoch(0L);
        newer.setRequestEvidenceJson("[{\"prefixState\":\"baseline\"}]");
        when(tasks.selectList(any())).thenReturn(List.of(olderTask, newerTask));
        when(snapshots.selectList(any())).thenReturn(List.of(older, newer));

        AgentRunConfigSnapshotService aggregateService = new AgentRunConfigSnapshotService(
                snapshots, mock(AgentEffectiveProjectConfigService.class),
                mock(AgentRunExecutionLeaseService.class), tasks);

        assertThat(aggregateService.prefixStatsForConversation("conversation-7").state())
                .isEqualTo("baseline");
    }

    @Test
    void latestEmptySnapshotDoesNotReuseAnOlderComparisonState() {
        AgentRunConfigSnapshotMapper snapshots = mock(AgentRunConfigSnapshotMapper.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentTask olderTask = new AgentTask();
        olderTask.setTaskId(71L);
        olderTask.setConversationId("conversation-7");
        AgentTask newerTask = new AgentTask();
        newerTask.setTaskId(72L);
        newerTask.setConversationId("conversation-7");
        AgentRunConfigSnapshot older = new AgentRunConfigSnapshot();
        older.setTaskId(71L);
        older.setRequestEvidenceJson("[{\"prefixState\":\"stable\",\"prefixStable\":true}]");
        AgentRunConfigSnapshot newer = new AgentRunConfigSnapshot();
        newer.setTaskId(72L);
        newer.setRequestEvidenceJson("[]");
        when(tasks.selectList(any())).thenReturn(List.of(olderTask, newerTask));
        when(snapshots.selectList(any())).thenReturn(List.of(older, newer));

        AgentRunConfigSnapshotService aggregateService = new AgentRunConfigSnapshotService(
                snapshots, mock(AgentEffectiveProjectConfigService.class),
                mock(AgentRunExecutionLeaseService.class), tasks);

        assertThat(aggregateService.prefixStatsForConversation("conversation-7").state())
                .isEqualTo("not_reported");
    }

    private static DataSource dataSource(String name) {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:" + name + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        dataSource.setUser("sa");
        dataSource.setPassword("");
        return dataSource;
    }

    private static SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration();
        configuration.addMapper(AgentRunConfigSnapshotMapper.class);
        configuration.setEnvironment(new Environment("test",
                new JdbcTransactionFactory(), dataSource));
        return new SqlSessionFactoryBuilderWithMapper().build(configuration, dataSource);
    }

    private static void createTable(DataSource dataSource) throws Exception {
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_run_config_snapshot (
                      snapshot_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                      task_id BIGINT NOT NULL,
                      execution_epoch BIGINT NOT NULL,
                      project_id INT,
                      project_config_revision BIGINT,
                      project_config_digest VARCHAR(64),
                      effective_config_json LONGTEXT,
                      effective_config_digest VARCHAR(64),
                      model_fingerprint VARCHAR(64),
                      capability_digest VARCHAR(64),
                      resource_digest VARCHAR(64),
                      runtime_profile VARCHAR(32),
                      network_policy_json LONGTEXT,
                      verification_policy_json LONGTEXT,
                      environment_operation_ref VARCHAR(128),
                      secret_aliases_json LONGTEXT,
                      request_evidence_json LONGTEXT,
                      create_time DATETIME,
                      update_time DATETIME,
                      UNIQUE KEY uk_task_epoch (task_id, execution_epoch)
                    )
                    """);
        }
    }

    private static final class SqlSessionFactoryBuilderWithMapper {
        SqlSessionFactory build(MybatisConfiguration configuration, DataSource dataSource) {
            return new org.apache.ibatis.session.SqlSessionFactoryBuilder().build(configuration);
        }
    }
}
