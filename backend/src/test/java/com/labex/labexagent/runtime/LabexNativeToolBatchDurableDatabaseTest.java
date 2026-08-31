package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.google.gson.JsonObject;
import com.labex.entity.AgentRunEvent;
import com.labex.entity.AgentRunMessage;
import com.labex.entity.AgentRunPart;
import com.labex.entity.AgentTask;
import com.labex.labexagent.llm.LlmProvider;
import com.labex.labexagent.run.AgentRunExecutionLeaseService;
import com.labex.labexagent.run.AgentRunLifecycleService;
import com.labex.labexagent.run.AgentRunMessageService;
import com.labex.labexagent.run.AgentRunPartService;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.run.AgentToolCallJournalService;
import com.labex.labexagent.run.ExecutionFence;
import com.labex.labexagent.tool.AgentTool;
import com.labex.labexagent.tool.ToolDefinition;
import com.labex.labexagent.tool.ToolResult;
import com.labex.mapper.AgentRunEventMapper;
import com.labex.mapper.AgentRunMessageMapper;
import com.labex.mapper.AgentRunOutboxMapper;
import com.labex.mapper.AgentRunPartMapper;
import com.labex.mapper.AgentTaskMapper;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;

class LabexNativeToolBatchDurableDatabaseTest {

    @Test
    void controlledProviderMultiToolTurnPersistsMessagesPartsAndEventsBeforeTheFirstExecution() throws Exception {
        try (DurableFixture fixture = durableFixture()) {
            AgentTask task = fixture.task();
            AgentModelTurnExecutor.ModelTurnResult modelTurn = controlledTwoToolTurn();
            List<LabexNativeToolBatchExecutor.Admission> admissions = modelTurn.toolCalls().stream()
                    .map(this::admission)
                    .toList();
            List<String> executionOrder = new ArrayList<>();
            AtomicBoolean verifiedBeforeFirstExecution = new AtomicBoolean(false);

            LabexNativeToolBatchExecutor.BatchResult result = fixture.batchExecutor().execute(
                    new LabexNativeToolBatchExecutor.BatchRequest(
                            fixture.fence(), task.getTaskId(), 4L, 2, "", admissions, () -> false),
                    admission -> {
                        if (executionOrder.isEmpty()) {
                            List<AgentRunPart> providerCalls = fixture.partMapper().selectList(
                                    new QueryWrapper<AgentRunPart>()
                                            .eq("task_id", task.getTaskId())
                                            .eq("part_type", "tool_call")
                                            .orderByAsc("sequence_number"));
                            assertThat(providerCalls)
                                    .extracting(AgentRunPart::getToolCallId)
                                    .containsExactly("call-read", "call-list");
                            assertThat(providerCalls)
                                    .extracting(AgentRunPart::getStatus)
                                    .containsOnly("pending");
                            verifiedBeforeFirstExecution.set(true);
                        }
                        executionOrder.add(admission.call().toolCallId());
                        return LabexNativeToolBatchExecutor.CallExecution.completed(
                                ToolResult.ok("result:" + admission.call().toolCallId()));
                    },
                    (admission, toolResult, kind) -> toolResult.getContent());

            assertThat(result.terminal()).isEqualTo(LabexNativeToolBatchExecutor.Terminal.CONTINUE);
            assertThat(verifiedBeforeFirstExecution).isTrue();
            assertThat(executionOrder).containsExactly("call-read", "call-list");

            List<AgentRunMessage> providerMessages = fixture.messageMapper().selectList(
                    new QueryWrapper<AgentRunMessage>()
                            .eq("task_id", task.getTaskId())
                            .likeRight("message_key", "provider:")
                            .orderByAsc("sequence_number"));
            assertThat(providerMessages).extracting(AgentRunMessage::getRole)
                    .containsExactly("assistant", "tool", "tool");

            List<AgentRunPart> providerResults = fixture.partMapper().selectList(new QueryWrapper<AgentRunPart>()
                    .eq("task_id", task.getTaskId())
                    .eq("part_type", "tool_result")
                    .orderByAsc("sequence_number"));
            assertThat(providerResults).extracting(AgentRunPart::getToolCallId)
                    .containsExactly("call-read", "call-list");
            assertThat(providerResults).extracting(AgentRunPart::getStatus)
                    .containsOnly("completed");

            List<AgentRunPart> journalledCalls = fixture.partMapper().selectList(new QueryWrapper<AgentRunPart>()
                    .eq("task_id", task.getTaskId())
                    .eq("part_type", "tool")
                    .orderByAsc("part_id"));
            assertThat(journalledCalls).extracting(AgentRunPart::getToolCallId)
                    .containsExactly("call-read", "call-list");
            assertThat(journalledCalls).extracting(AgentRunPart::getStatus)
                    .containsOnly("completed");

            List<AgentRunEvent> events = fixture.eventMapper().selectList(new QueryWrapper<AgentRunEvent>()
                    .eq("task_id", task.getTaskId())
                    .eq("event_type", "TOOL_CALL_STATE")
                    .orderByAsc("sequence_number"));
            assertThat(events).hasSizeGreaterThanOrEqualTo(4);
            assertThat(events.stream().map(AgentRunEvent::getPayload).toList())
                    .anySatisfy(payload -> assertThat(payload).contains("call-read").contains("pending"))
                    .anySatisfy(payload -> assertThat(payload).contains("call-list").contains("pending"))
                    .anySatisfy(payload -> assertThat(payload).contains("call-read").contains("completed"))
                    .anySatisfy(payload -> assertThat(payload).contains("call-list").contains("completed"));
        }
    }

    @Test
    void approvalKeepsTheRunNonFinalAndPersistsTheRemainingToolAsSkipped() throws Exception {
        try (DurableFixture fixture = durableFixture()) {
            AgentTask task = fixture.task();
            List<LabexNativeToolBatchExecutor.Admission> admissions = controlledTwoToolTurn().toolCalls().stream()
                    .map(this::admission)
                    .toList();
            AtomicBoolean secondToolExecuted = new AtomicBoolean(false);

            LabexNativeToolBatchExecutor.BatchResult result = fixture.batchExecutor().execute(
                    new LabexNativeToolBatchExecutor.BatchRequest(
                            fixture.fence(), task.getTaskId(), 4L, 2, "", admissions, () -> false),
                    admission -> {
                        if ("call-read".equals(admission.call().toolCallId())) {
                            return LabexNativeToolBatchExecutor.CallExecution.completed(
                                    ToolResult.commandApprovalRequired(
                                            "approval required", "approval-call-read", "rm README.md",
                                            "high", "destructive", "2026-08-16T00:00:00Z"));
                        }
                        secondToolExecuted.set(true);
                        return LabexNativeToolBatchExecutor.CallExecution.completed(ToolResult.ok("unexpected"));
                    },
                    (admission, toolResult, kind) -> toolResult.getContent());

            assertThat(result.terminal()).isEqualTo(LabexNativeToolBatchExecutor.Terminal.WAITING_APPROVAL);
            assertThat(result.terminalOutcome().admission().call().toolCallId()).isEqualTo("call-read");
            assertThat(secondToolExecuted).isFalse();
            assertThat(fixture.taskMapper().selectById(task.getTaskId()).getStatus()).isEqualTo("running");

            List<AgentRunPart> journalledCalls = fixture.partMapper().selectList(new QueryWrapper<AgentRunPart>()
                    .eq("task_id", task.getTaskId())
                    .eq("part_type", "tool")
                    .orderByAsc("part_id"));
            assertThat(journalledCalls).extracting(AgentRunPart::getToolCallId)
                    .containsExactly("call-read", "call-list");
            assertThat(journalledCalls).extracting(AgentRunPart::getStatus)
                    .containsExactly("waiting_approval", "skipped");

            List<AgentRunPart> providerResults = fixture.partMapper().selectList(new QueryWrapper<AgentRunPart>()
                    .eq("task_id", task.getTaskId())
                    .eq("part_type", "tool_result")
                    .orderByAsc("sequence_number"));
            assertThat(providerResults).extracting(AgentRunPart::getToolCallId)
                    .containsExactly("call-list");

            List<AgentRunEvent> events = fixture.eventMapper().selectList(new QueryWrapper<AgentRunEvent>()
                    .eq("task_id", task.getTaskId())
                    .eq("event_type", "TOOL_CALL_STATE")
                    .orderByAsc("sequence_number"));
            assertThat(events.stream().map(AgentRunEvent::getPayload).toList())
                    .anySatisfy(payload -> assertThat(payload).contains("call-read").contains("waiting_approval"))
                    .anySatisfy(payload -> assertThat(payload).contains("call-list").contains("skipped"));
        }
    }

    private AgentModelTurnExecutor.ModelTurnResult controlledTwoToolTurn() throws Exception {
        return new AgentModelTurnExecutor(5_000L).execute(new AgentModelTurnExecutor.ModelTurnRequest(
                "system", List.of(Map.of("role", "user", "content", "inspect files")), List.of(),
                new TwoToolProvider(), new LlmProvider.LlmConfig("test", "https://example.test", "controlled", 256, 0.0),
                1, 71L, "en", CancellationToken.none(), new NoopEventSink()));
    }

    private LabexNativeToolBatchExecutor.Admission admission(AgentModelTurnExecutor.NativeToolCall call) {
        JsonObject arguments = com.google.gson.JsonParser.parseString(call.toolArguments()).getAsJsonObject();
        AgentTool tool = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return ToolDefinition.builder().name(call.toolName()).description(call.toolName()).build();
            }

            @Override
            public ToolResult execute(AgentContext context, JsonObject ignored) {
                return ToolResult.ok("not executed by this fixture");
            }
        };
        return new LabexNativeToolBatchExecutor.Admission(call,
                AgentToolTurnExecutor.ToolInputResolution.allowed(arguments, tool), arguments);
    }

    private AgentTask runningTask() {
        AgentTask task = new AgentTask();
        task.setConversationId("native-durable-test");
        task.setStudentId(7);
        task.setProjectId(12);
        task.setMode("build");
        task.setRuntimeProfile("labex-native");
        task.setStatus("running");
        task.setRunVersion(0L);
        task.setLastEventSequence(0L);
        task.setExecutionEpoch(4L);
        task.setExecutionOwner("instance-native-test");
        task.setExecutionLeaseExpiresAt(LocalDateTime.now().plusMinutes(5));
        task.setExecutionHeartbeatAt(LocalDateTime.now());
        task.setCreateTime(LocalDateTime.now());
        task.setUpdateTime(LocalDateTime.now());
        return task;
    }

    private DurableFixture durableFixture() throws Exception {
        SqlSession session = factory(dataSource()).openSession(true);
        try {
            AgentTaskMapper taskMapper = session.getMapper(AgentTaskMapper.class);
            AgentRunMessageMapper messageMapper = session.getMapper(AgentRunMessageMapper.class);
            AgentRunPartMapper partMapper = session.getMapper(AgentRunPartMapper.class);
            AgentRunEventMapper eventMapper = session.getMapper(AgentRunEventMapper.class);
            AgentRunOutboxMapper outboxMapper = session.getMapper(AgentRunOutboxMapper.class);
            AgentTask task = runningTask();
            taskMapper.insert(task);
            ExecutionFence fence = new ExecutionFence(task.getTaskId(), "instance-native-test", 4L);
            AgentRunExecutionLeaseService leases = new AgentRunExecutionLeaseService(
                    taskMapper, "instance-native-test", 30_000L);
            AgentRunMessageService messages = new AgentRunMessageService(messageMapper, taskMapper, leases);
            AgentRunPartService parts = new AgentRunPartService(partMapper, taskMapper, messages, leases);
            AgentRunLifecycleService lifecycle = new AgentRunLifecycleService(
                    taskMapper, eventMapper, outboxMapper, null, leases);
            AgentToolCallJournalService journal = new AgentToolCallJournalService(lifecycle, parts);
            AgentRunTranscriptService transcript = new AgentRunTranscriptService(
                    messageMapper, partMapper, taskMapper, leases);
            LabexNativeToolBatchExecutor batchExecutor = new LabexNativeToolBatchExecutor(
                    new AgentToolCallBatchProtocol(),
                    new AgentProviderTranscriptAppender(new AgentProviderMessageProjector(), transcript),
                    journal);
            return new DurableFixture(session, task, fence, taskMapper, messageMapper, partMapper,
                    eventMapper, batchExecutor);
        } catch (Exception error) {
            session.close();
            throw error;
        }
    }

    private SqlSessionFactory factory(DataSource dataSource) {
        MybatisConfiguration configuration = new MybatisConfiguration(
                new Environment("native-tool-batch", new JdbcTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.addMapper(AgentTaskMapper.class);
        configuration.addMapper(AgentRunMessageMapper.class);
        configuration.addMapper(AgentRunPartMapper.class);
        configuration.addMapper(AgentRunEventMapper.class);
        configuration.addMapper(AgentRunOutboxMapper.class);
        return new com.baomidou.mybatisplus.core.MybatisSqlSessionFactoryBuilder().build(configuration);
    }

    private DataSource dataSource() throws Exception {
        JdbcDataSource dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:native_tool_batch_" + UUID.randomUUID().toString().replace("-", "")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1");
        createTables(dataSource);
        return dataSource;
    }

    private void createTables(DataSource dataSource) throws Exception {
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE t_agent_task (
                        task_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        conversation_id VARCHAR(64),
                        session_id VARCHAR(128),
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        title VARCHAR(500),
                        mode VARCHAR(32),
                        model_config_id INT,
                        runtime_profile VARCHAR(32),
                        status VARCHAR(32) NOT NULL,
                        current_step VARCHAR(500),
                        summary CLOB,
                        run_version BIGINT,
                        last_event_sequence BIGINT,
                        request_payload CLOB,
                        origin_message_id BIGINT,
                        parent_task_id BIGINT,
                        recovery_attempts INT,
                        retry_attempts INT,
                        next_retry_at TIMESTAMP,
                        execution_epoch BIGINT,
                        execution_owner VARCHAR(128),
                        execution_lease_expires_at TIMESTAMP,
                        execution_heartbeat_at TIMESTAMP,
                        background_branch VARCHAR(160),
                        background_worktree VARCHAR(2048),
                        background_base_ref VARCHAR(128),
                        background_cleanup_status VARCHAR(32),
                        submitted_at TIMESTAMP,
                        started_at TIMESTAMP,
                        active_segment_started_at TIMESTAMP,
                        finished_at TIMESTAMP,
                        elapsed_ms BIGINT,
                        active_elapsed_ms BIGINT,
                        create_time TIMESTAMP,
                        update_time TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE t_agent_run_message (
                        run_message_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        task_id BIGINT NOT NULL,
                        conversation_id VARCHAR(64),
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        message_key VARCHAR(160) NOT NULL,
                        sequence_number BIGINT,
                        parent_message_id BIGINT,
                        conversation_sequence BIGINT,
                        role VARCHAR(32) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        content CLOB,
                        metadata CLOB,
                        create_time TIMESTAMP,
                        update_time TIMESTAMP,
                        UNIQUE(task_id, message_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE t_agent_run_part (
                        part_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        task_id BIGINT NOT NULL,
                        conversation_id VARCHAR(64),
                        message_id BIGINT,
                        student_id INT NOT NULL,
                        project_id INT NOT NULL,
                        part_key VARCHAR(160) NOT NULL,
                        sequence_number BIGINT,
                        part_type VARCHAR(32) NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        tool_call_id VARCHAR(160),
                        tool_name VARCHAR(128),
                        input_json CLOB,
                        output_text CLOB,
                        metadata CLOB,
                        tail_start_message_id BIGINT,
                        create_time TIMESTAMP,
                        update_time TIMESTAMP,
                        UNIQUE(task_id, part_key)
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
                        payload CLOB,
                        idempotency_key VARCHAR(128) NOT NULL,
                        create_time TIMESTAMP,
                        UNIQUE(task_id, sequence_number),
                        UNIQUE(task_id, idempotency_key)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE t_agent_run_outbox (
                        outbox_id BIGINT AUTO_INCREMENT PRIMARY KEY,
                        event_id BIGINT NOT NULL,
                        task_id BIGINT NOT NULL,
                        topic VARCHAR(80) NOT NULL,
                        payload CLOB NOT NULL,
                        status VARCHAR(32) NOT NULL,
                        attempts INT NOT NULL,
                        available_time TIMESTAMP NOT NULL,
                        published_time TIMESTAMP,
                        create_time TIMESTAMP,
                        UNIQUE(event_id)
                    )
                    """);
        }
    }

    private record DurableFixture(SqlSession session, AgentTask task, ExecutionFence fence,
                                  AgentTaskMapper taskMapper, AgentRunMessageMapper messageMapper,
                                  AgentRunPartMapper partMapper, AgentRunEventMapper eventMapper,
                                  LabexNativeToolBatchExecutor batchExecutor) implements AutoCloseable {
        @Override
        public void close() {
            session.close();
        }
    }

    private static final class NoopEventSink implements AgentModelTurnExecutor.EventSink {
        @Override
        public void durable(String type, Object data) {
        }

        @Override
        public void transientEvent(String type, Object data) {
        }
    }

    private static final class TwoToolProvider implements LlmProvider {
        @Override
        public String getProviderId() {
            return "controlled-test";
        }

        @Override
        public String getProviderName() {
            return "controlled-test";
        }

        @Override
        public boolean supportsStreaming() {
            return true;
        }

        @Override
        public boolean supportsToolCalling() {
            return true;
        }

        @Override
        public Map<String, Object> chatWithTools(String sysPrompt, List<Map<String, Object>> messages,
                                                 List<Map<String, Object>> tools, LlmConfig config) {
            return Map.of();
        }

        @Override
        public void chatStream(String sysPrompt, List<Map<String, Object>> messages,
                               List<Map<String, Object>> tools, LlmConfig config,
                               java.util.function.Consumer<StreamChunk> onChunk) {
            onChunk.accept(new StreamChunk("tool_call", "", "read_file", "{\"path\":\"README.md\"}",
                    "", true, null, "call-read", 0, null));
            onChunk.accept(new StreamChunk("tool_call", "", "list_files", "{\"path\":\"src\"}",
                    "", true, null, "call-list", 1, null));
            onChunk.accept(new StreamChunk("done", "", null, null, null, true, null));
        }
    }
}
