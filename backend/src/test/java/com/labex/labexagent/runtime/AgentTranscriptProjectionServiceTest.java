package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentConversation;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.AgentTask;
import com.labex.labexagent.attachment.AgentInputAttachmentService;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.context.AgentRequestTokenEstimator;
import com.labex.labexagent.run.AgentConversationMessageGraphVersion;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import com.labex.labexagent.service.AgentConversationMemoryProjectionService;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.service.AgentModelConfigService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentTranscriptProjectionServiceTest {

    @Test
    void doesNotExposeTheRetiredMemoryFallbackProjectionApi() {
        List<String> methodNames = Arrays.stream(AgentTranscriptProjectionService.class.getDeclaredMethods())
                .map(java.lang.reflect.Method::getName)
                .toList();

        assertThat(methodNames).doesNotContain("projectForProvider", "project");
    }

    @Test
    void providerLoaderReadsDurableProjectionWithoutAcceptingMemoryInput() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        List<Map<String, Object>> messages = List.of(Map.of("role", "user", "content", "durable"));
        when(transcript.loadProjectableTranscript(7L)).thenReturn(messages);

        assertThat(service(transcript).loadProviderMessages(7L))
                .isEqualTo(messages);
    }

    @Test
    void providerProjectionPrependsStableSameConversationHistoryWithoutPollutingCurrentTaskTranscript() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentConversationMemoryProjectionService conversationMemory =
                mock(AgentConversationMemoryProjectionService.class);
        AgentTask currentTask = task(1980L, 279, 88, "conversation-48fba2f7");
        List<Map<String, Object>> priorStableMessages = List.of(
                Map.of("role", "user", "content", "请给出产品迭代方向"),
                Map.of("role", "assistant", "content", "已确认：优先实现真实事实链。"));
        List<Map<String, Object>> currentTaskMessages = List.of(
                Map.of("role", "user", "content", "当前任务的初始化上下文"),
                Map.of("role", "user", "content", "把刚刚确定的方向写成文档并开始实施"));
        when(transcript.loadProjectableTranscript(1980L)).thenReturn(currentTaskMessages);
        when(tasks.selectById(1980L)).thenReturn(currentTask);
        when(conversationMemory.project(279, 88, "conversation-48fba2f7", 1980L))
                .thenReturn(new AgentConversationMemoryProjectionService.Projection(
                        priorStableMessages, 1979L, 1));
        AgentTranscriptProjectionService service = serviceWithConversationProjection(
                transcript, tasks, conversationMemory);

        assertThat(service.loadDurableProjection(1980L).messages())
                .containsExactlyElementsOf(currentTaskMessages);
        assertThat(service.loadProviderMessages(1980L)).containsExactlyElementsOf(List.of(
                priorStableMessages.get(0), priorStableMessages.get(1),
                currentTaskMessages.get(0), currentTaskMessages.get(1)));
        verify(conversationMemory).project(279, 88, "conversation-48fba2f7", 1980L);
    }

    @Test
    void nativeConversationGraphVersionRoutesDirectlyToGraphProjectorWithoutLegacyHistoryPrefix() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentConversationMemoryProjectionService conversationMemory =
                mock(AgentConversationMemoryProjectionService.class);
        AgentConversationMessageGraphProjector graphProjector =
                mock(AgentConversationMessageGraphProjector.class);
        AgentTask currentTask = task(1980L, 279, 88, "conversation-48fba2f7");
        currentTask.setRuntimeProfile(AgentRuntimeProfile.LABEX_NATIVE.persistedValue());
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("conversation-48fba2f7");
        conversation.setStudentId(279);
        conversation.setProjectId(88);
        conversation.setStatus(1);
        conversation.setHistoryProjectionVersion(AgentConversationMessageGraphVersion.VALUE);
        List<Map<String, Object>> graphMessages = List.of(
                Map.of("role", "user", "content", "真实历史请求"),
                Map.of("role", "assistant", "content", "真实历史回复"));
        when(tasks.selectById(1980L)).thenReturn(currentTask);
        when(conversations.selectOne(org.mockito.ArgumentMatchers.any())).thenReturn(conversation);
        when(graphProjector.projectForProvider(new AgentConversationMessageGraphProjector.Request(
                279, 88, "conversation-48fba2f7"))).thenReturn(graphMessages);
        AgentTranscriptProjectionService service = serviceWithGraphProjection(
                transcript, tasks, conversationMemory, conversations, graphProjector);

        assertThat(service.loadProviderMessages(1980L)).containsExactlyElementsOf(graphMessages);

        verify(graphProjector).projectForProvider(new AgentConversationMessageGraphProjector.Request(
                279, 88, "conversation-48fba2f7"));
        verifyNoInteractions(transcript, conversationMemory);
    }

    @Test
    void taskWithoutConversationIdentityKeepsOnlyItsOwnDurableTranscript() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentConversationMemoryProjectionService conversationMemory =
                mock(AgentConversationMemoryProjectionService.class);
        List<Map<String, Object>> currentTaskMessages = List.of(
                Map.of("role", "user", "content", "standalone task request"));
        when(transcript.loadProjectableTranscript(1981L)).thenReturn(currentTaskMessages);
        when(tasks.selectById(1981L)).thenReturn(task(1981L, 279, 88, null));
        AgentTranscriptProjectionService service = serviceWithConversationProjection(
                transcript, tasks, conversationMemory);

        assertThat(service.loadProviderMessages(1981L)).containsExactlyElementsOf(currentTaskMessages);
        verifyNoInteractions(conversationMemory);
    }

    @Test
    void interactionResumeProjectionRemainsCurrentTaskOnlyWhenConversationHistoryIsAvailable() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentConversationMemoryProjectionService conversationMemory =
                mock(AgentConversationMemoryProjectionService.class);
        List<Map<String, Object>> resumableCurrentTaskMessages = List.of(
                Map.of("role", "user", "content", "resume the approved tool call"));
        when(transcript.loadProjectableTranscriptForInteractionResume(1980L))
                .thenReturn(resumableCurrentTaskMessages);
        AgentTranscriptProjectionService service = serviceWithConversationProjection(
                transcript, tasks, conversationMemory);

        assertThat(service.loadDurableProjectionForInteractionResume(1980L).messages())
                .containsExactlyElementsOf(resumableCurrentTaskMessages);
        verifyNoInteractions(tasks, conversationMemory);
    }

    @Test
    void providerProjectionFailsClosedWhenTheCurrentTaskOwnershipFactIsUnavailable() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentConversationMemoryProjectionService conversationMemory =
                mock(AgentConversationMemoryProjectionService.class);
        when(transcript.loadProjectableTranscript(1980L))
                .thenReturn(List.of(Map.of("role", "user", "content", "current request")));
        AgentTranscriptProjectionService service = serviceWithConversationProjection(
                transcript, tasks, conversationMemory);

        assertThatThrownBy(() -> service.loadProviderMessages(1980L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Durable task is unavailable for Provider conversation projection");
    }

    @Test
    void hydratesDurableAttachmentReferencesOnlyAtProviderProjectionBoundary() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentInputAttachmentService attachments = mock(AgentInputAttachmentService.class);
        List<Map<String, Object>> durable = List.of(Map.of(
                "role", "user", "content", "inspect this", "attachmentIds", List.of("image-1")));
        List<Map<String, Object>> hydrated = List.of(Map.of(
                "role", "user", "content", List.of(Map.of("type", "text", "text", "inspect this"))));
        when(transcript.loadProjectableTranscript(7L)).thenReturn(durable);
        when(attachments.hydrateProviderMessage(7L, durable.get(0), false)).thenReturn(hydrated.get(0));

        AgentTranscriptProjectionService service = new AgentTranscriptProjectionService(
                transcript, new AgentProviderMessageProjector(), mock(AgentCompactionService.class), attachments);

        assertThat(service.loadProviderMessages(7L)).isEqualTo(hydrated);
        org.mockito.Mockito.verify(attachments).hydrateProviderMessage(7L, durable.get(0), false);
    }

    @Test
    void latestImageCarrierIsHydratedWhileConsumedCarriersAreFlaggedAsModelConsumed() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentInputAttachmentService attachments = mock(AgentInputAttachmentService.class);
        Map<String, Object> consumedCarrier = Map.of(
                "role", "user", "content", "earlier turn with image", "attachmentIds", List.of("image-old"));
        Map<String, Object> assistantReply = Map.of("role", "assistant", "content", "seen it");
        Map<String, Object> latestCarrier = Map.of(
                "role", "user", "content", "another screenshot", "attachmentIds", List.of("image-new"));
        when(transcript.loadProjectableTranscript(11L))
                .thenReturn(List.of(consumedCarrier, assistantReply, latestCarrier));
        Map<String, Object> degraded = Map.of("role", "user", "content",
                "earlier turn with image\n[Image attachments omitted]");
        when(attachments.hydrateProviderMessage(11L, consumedCarrier, true)).thenReturn(degraded);
        when(attachments.hydrateProviderMessage(11L, assistantReply, false)).thenReturn(assistantReply);
        Map<String, Object> hydratedLatest = Map.of("role", "user",
                "content", List.of(Map.of("type", "text", "text", "another screenshot")));
        when(attachments.hydrateProviderMessage(11L, latestCarrier, false)).thenReturn(hydratedLatest);

        AgentTranscriptProjectionService service = new AgentTranscriptProjectionService(
                transcript, new AgentProviderMessageProjector(), mock(AgentCompactionService.class), attachments);

        assertThat(service.loadProviderMessages(11L))
                .containsExactly(degraded, assistantReply, hydratedLatest);
        org.mockito.Mockito.verify(attachments).hydrateProviderMessage(11L, consumedCarrier, true);
        org.mockito.Mockito.verify(attachments).hydrateProviderMessage(11L, latestCarrier, false);
    }

    @Test
    void imageCarrierWithoutSubsequentAssistantReplyStaysHydratable() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentInputAttachmentService attachments = mock(AgentInputAttachmentService.class);
        Map<String, Object> firstCarrier = Map.of(
                "role", "user", "content", "first image turn", "attachmentIds", List.of("image-1"));
        Map<String, Object> assistantReply = Map.of("role", "assistant", "content", "done");
        Map<String, Object> plainFollowUp = Map.of("role", "user", "content", "continue");
        when(transcript.loadProjectableTranscript(12L))
                .thenReturn(List.of(firstCarrier, assistantReply, plainFollowUp));
        Map<String, Object> degraded = Map.of("role", "user", "content",
                "first image turn\n[Image attachments omitted]");
        when(attachments.hydrateProviderMessage(12L, firstCarrier, true)).thenReturn(degraded);
        when(attachments.hydrateProviderMessage(12L, assistantReply, false)).thenReturn(assistantReply);
        when(attachments.hydrateProviderMessage(12L, plainFollowUp, false)).thenReturn(plainFollowUp);

        AgentTranscriptProjectionService service = new AgentTranscriptProjectionService(
                transcript, new AgentProviderMessageProjector(), mock(AgentCompactionService.class), attachments);

        assertThat(service.loadProviderMessages(12L))
                .containsExactly(degraded, assistantReply, plainFollowUp);
        org.mockito.Mockito.verify(attachments).hydrateProviderMessage(12L, firstCarrier, true);
    }

    @Test
    void compactionViewKeepsAttachmentReferencesUnhydratedSoSelectionPersistenceStaysBase64Free() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentInputAttachmentService attachments = mock(AgentInputAttachmentService.class);
        List<Map<String, Object>> durable = List.of(Map.of(
                "role", "user", "content", "inspect this screenshot",
                "attachmentIds", List.of("image-1", "image-2")));
        when(transcript.loadProjectableTranscript(9L)).thenReturn(durable);

        AgentTranscriptProjectionService service = new AgentTranscriptProjectionService(
                transcript, new AgentProviderMessageProjector(), mock(AgentCompactionService.class), attachments);

        AgentTranscriptProjectionService.Projection view = service.loadDurableCompactionView(9L);

        // 选材视图必须保留 durable 引用形态：Base64 只允许存在于 Provider 请求边界，
        // 绝不能随 CompactionSelection 序列化进 t_agent_compaction_record。
        assertThat(view.messages().get(0).get("content")).isEqualTo("inspect this screenshot");
        assertThat(view.messages().get(0).get("attachmentIds")).isEqualTo(List.of("image-1", "image-2"));
        assertThat(String.valueOf(view.messages())).doesNotContain("image_url");
        verifyNoInteractions(attachments);
    }

    @Test
    void providerLoaderFailsClosedWhenDurableProjectionIsEmpty() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        when(transcript.loadProjectableTranscript(7L)).thenReturn(List.of());

        assertThatThrownBy(() -> service(transcript).loadProviderMessages(7L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Durable Provider transcript is empty");
    }

    @Test
    void rejectsMissingDurableTaskIdentity() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);

        assertThatThrownBy(() -> service(transcript).loadDurableProjection(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive taskId");
    }

    @Test
    void appliesLatestCompletedCompactionForRestartRestore() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        AgentCompactionService compactions = mock(AgentCompactionService.class);
        List<Map<String, Object>> compacted = List.of(
                Map.of("role", "user", "content", "durable summary"),
                Map.of("role", "user", "content", "retained"),
                Map.of("role", "assistant", "content", "answer"),
                Map.of("role", "user", "content", "later"));
        when(compactions.projectLatest(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.Optional.of(new AgentCompactionService.Projection(compacted, 2L, 9L)));
        AgentTranscriptProjectionService service = new AgentTranscriptProjectionService(
                transcript, new AgentProviderMessageProjector(), compactions);

        AgentTranscriptProjectionService.Projection restored = service.loadDurableProjection(7L);

        assertThat(restored.messages()).isEqualTo(compacted);
        assertThat(restored.detail()).contains("compaction_epoch=2");
    }

    private AgentTask task(Long taskId, Integer studentId, Integer projectId, String conversationId) {
        AgentTask task = new AgentTask();
        task.setTaskId(taskId);
        task.setStudentId(studentId);
        task.setProjectId(projectId);
        task.setConversationId(conversationId);
        return task;
    }

    private AgentTranscriptProjectionService serviceWithGraphProjection(
            AgentRunTranscriptService transcript, AgentTaskMapper tasks,
            AgentConversationMemoryProjectionService conversationMemory,
            AgentConversationMapper conversations, AgentConversationMessageGraphProjector graphProjector) {
        return new AgentTranscriptProjectionService(transcript, new AgentProviderMessageProjector(),
                mock(AgentCompactionService.class), null, tasks, conversationMemory, conversations, graphProjector);
    }

    private AgentTranscriptProjectionService serviceWithConversationProjection(
            AgentRunTranscriptService transcript, AgentTaskMapper tasks,
            AgentConversationMemoryProjectionService conversationMemory) {
        return new AgentTranscriptProjectionService(transcript, new AgentProviderMessageProjector(),
                mock(AgentCompactionService.class), null, tasks, conversationMemory);
    }

    private AgentTranscriptProjectionService service(AgentRunTranscriptService transcript) {
        return new AgentTranscriptProjectionService(transcript, new AgentProviderMessageProjector(),
                mock(AgentCompactionService.class));
    }

    /**
     * characterization：占位化默认关闭时，Provider 投影必须与历史行为逐字一致。
     * 这条锁住"新增能力默认零影响"，避免默认值漂移导致线上请求悄悄变短。
     */
    @Test
    void leavesHistoricalToolOutputUntouchedWhenCompactionIsDisabledByDefault() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        List<Map<String, Object>> messages = toolHistory();
        when(transcript.loadProjectableTranscript(7L)).thenReturn(messages);

        assertThat(service(transcript).loadProviderMessages(7L)).isEqualTo(messages);
    }

    /** 开启后：安全尾部之外的旧工具结果被占位化，协议字段与原始顺序保持不变。 */
    @Test
    void compactsOnlyHistoricalToolOutputBeyondTheProtectedTail() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        List<Map<String, Object>> messages = toolHistory();
        when(transcript.loadProjectableTranscript(7L)).thenReturn(messages);

        List<Map<String, Object>> projected =
                serviceWithToolOutputCompaction(transcript, true).loadProviderMessages(7L);

        assertThat(projected).hasSize(messages.size());
        // 最旧的 grep 结果超出门槛，被占位化；tool_call_id 与 name 必须原样保留以维持协议配对。
        Map<String, Object> compacted = projected.get(2);
        assertThat(compacted).containsEntry("role", "tool")
                .containsEntry("tool_call_id", "call-grep")
                .containsEntry("name", "grep");
        assertThat(String.valueOf(compacted.get("content"))).contains("[Old tool result content cleared.");
        // 落在最近 tailTurns 轮内的结果不受影响。
        assertThat(projected.get(6)).containsEntry("content", messages.get(6).get("content"));
        // read_file 属于受保护工具白名单之外的普通读取，但在 tail 内，同样不动。
        assertThat(projected.get(8)).containsEntry("content", messages.get(8).get("content"));
        // 输入列表本身不被就地修改。
        assertThat(messages.get(2)).containsEntry("content", tool("grep", "match\n" + "g".repeat(900)));
    }

    /** 受保护工具（skill 输出无法重建）即使超出保护区也不得占位化。 */
    @Test
    void neverCompactsProtectedToolOutput() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        List<Map<String, Object>> messages = List.of(
                Map.of("role", "user", "content", "turn-1"),
                Map.of("role", "assistant", "content", ""),
                Map.of("role", "tool", "tool_call_id", "call-skill", "name", "skill",
                        "content", tool("skill", "manual\n" + "s".repeat(900))),
                Map.of("role", "assistant", "content", ""),
                Map.of("role", "user", "content", "turn-2"),
                Map.of("role", "assistant", "content", ""),
                Map.of("role", "user", "content", "turn-3"));
        when(transcript.loadProjectableTranscript(7L)).thenReturn(messages);

        List<Map<String, Object>> projected =
                serviceWithToolOutputCompaction(transcript, true).loadProviderMessages(7L);

        assertThat(projected.get(2)).containsEntry("content", tool("skill", "manual\n" + "s".repeat(900)));
    }

    /**
     * 压缩选材视图必须保留完整工具输出：摘要要读到原文，且选材结果会整体序列化进压缩记录，
     * 绝不能把占位文本写进去。
     */
    @Test
    void keepsFullToolOutputInTheCompactionSelectionView() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        List<Map<String, Object>> messages = toolHistory();
        when(transcript.loadProjectableTranscript(7L)).thenReturn(messages);

        AgentTranscriptProjectionService service =
                serviceWithToolOutputCompaction(transcript, true);

        assertThat(service.loadDurableCompactionView(7L).messages()).isEqualTo(messages);
    }

    /**
     * 开关的唯一权威是模型级 {@code compactionPrune}：置 0 时即使存在远超保护额度的历史工具输出，
     * Provider 投影也必须逐字保留原文。这条锁住"唯一的关闭路径真的能关"。
     */
    @Test
    void respectsTheModelLevelPruneSwitchWhenItIsTurnedOff() {
        AgentRunTranscriptService transcript = mock(AgentRunTranscriptService.class);
        List<Map<String, Object>> messages = toolHistory();
        when(transcript.loadProjectableTranscript(7L)).thenReturn(messages);

        assertThat(serviceWithToolOutputCompaction(transcript, false).loadProviderMessages(7L))
                .isEqualTo(messages);
    }

    private AgentLoopProperties compactionTuning() {
        AgentLoopProperties properties = new AgentLoopProperties();
        // 用小阈值锁定机制本身：默认 40k/20k 需要极长会话才会触发，不适合作为单测输入。
        properties.setPruneProtectTokens(10);
        properties.setPruneMinimumTokens(1);
        return properties;
    }

    /**
     * 占位化的开关权威是模型级 {@code compactionPrune}，因此测试必须通过模型配置驱动，
     * 不能靠第二个全局开关绕开——否则就回到了"两个开关管一件事"。
     */
    private AgentTranscriptProjectionService serviceWithToolOutputCompaction(
            AgentRunTranscriptService transcript, boolean pruningEnabled) {
        AgentTaskMapper tasks = mock(AgentTaskMapper.class);
        AgentConversationMemoryProjectionService conversationMemory =
                mock(AgentConversationMemoryProjectionService.class);
        // task 不带 native profile，因此不会走到 conversation graph 分支；两个 graph 依赖仅为满足
        // 生产构造器的非空校验。
        AgentTask currentTask = task(7L, 279, 88, "conv-compaction");
        currentTask.setModelConfigId(42);
        when(tasks.selectById(7L)).thenReturn(currentTask);
        when(conversationMemory.project(279, 88, "conv-compaction", 7L))
                .thenReturn(new AgentConversationMemoryProjectionService.Projection(List.of(), 6L, 0));

        AgentModelConfig modelConfig = new AgentModelConfig();
        modelConfig.setConfigId(42);
        modelConfig.setModelName("primary");
        modelConfig.setStatus(1);
        modelConfig.setMaxTokens(4096);
        modelConfig.setContextWindowTokens(32768);
        modelConfig.setCompactionPrune(pruningEnabled ? 1 : 0);
        AgentModelConfigService modelConfigService = mock(AgentModelConfigService.class);
        when(modelConfigService.getOwned(279, 42)).thenReturn(modelConfig);

        AgentTranscriptProjectionService service = new AgentTranscriptProjectionService(
                transcript, new AgentProviderMessageProjector(), mock(AgentCompactionService.class), null,
                tasks, conversationMemory, mock(AgentConversationMapper.class),
                mock(AgentConversationMessageGraphProjector.class), compactionTuning(),
                new AgentRequestTokenEstimator(), modelConfigService);
        return service;
    }

    private List<Map<String, Object>> toolHistory() {
        return List.of(
                Map.of("role", "user", "content", "turn-1"),
                Map.of("role", "assistant", "content", ""),
                Map.of("role", "tool", "tool_call_id", "call-grep", "name", "grep",
                        "content", tool("grep", "match\n" + "g".repeat(900))),
                Map.of("role", "assistant", "content", ""),
                Map.of("role", "user", "content", "turn-2"),
                Map.of("role", "assistant", "content", ""),
                Map.of("role", "tool", "tool_call_id", "call-read", "name", "read_file",
                        "content", tool("read_file", "body\n" + "r".repeat(900))),
                Map.of("role", "assistant", "content", ""),
                Map.of("role", "user", "content", "turn-3"));
    }

    private String tool(String name, String output) {
        return "[Tool " + name + " result]\n" + output;
    }
}
