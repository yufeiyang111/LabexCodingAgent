package com.labex.labexagent.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentConversation;
import com.labex.entity.AgentTask;
import com.labex.labexagent.attachment.AgentInputAttachmentService;
import com.labex.labexagent.context.AgentCompactionService;
import com.labex.labexagent.run.AgentConversationMessageGraphVersion;
import com.labex.labexagent.run.AgentRunTranscriptService;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import com.labex.labexagent.service.AgentConversationMemoryProjectionService;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentTaskMapper;
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
}
