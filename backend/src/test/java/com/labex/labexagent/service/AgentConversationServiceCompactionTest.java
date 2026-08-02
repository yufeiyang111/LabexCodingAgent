package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentMessage;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.labexagent.runtime.CompactionAgent;
import com.labex.rag.config.RagConfig;
import com.labex.service.AgentModelConfigService;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentConversationServiceCompactionTest {

    @BeforeAll
    static void initializeEntityMetadata() {
        initializeEntityMetadata(AgentConversation.class);
        initializeEntityMetadata(AgentMessage.class);
    }

    private static void initializeEntityMetadata(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }

    @Test
    void finalEventPersistsOnlyTheVisibleReasoningProjection() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentConversationService service = new AgentConversationService(
                conversationMapper, messageMapper, mock(RagConfig.class));
        ArgumentCaptor<AgentMessage> messageCaptor = ArgumentCaptor.forClass(AgentMessage.class);

        service.saveEvent(conversation(), "FINAL", Map.of(
                "content", "Visible <THINK data-kind='hidden'>private plan</THINKING> answer",
                "summary", "done"));

        verify(messageMapper).insert(messageCaptor.capture());
        AgentMessage persisted = messageCaptor.getValue();
        assertEquals("Visible  answer", persisted.getContent());
        assertFalse(persisted.getEventData().toLowerCase().contains("<think"));
        assertFalse(persisted.getEventData().contains("private plan"));
    }

    @Test
    void recoveryUsesLatestCompactionSummaryAndOnlyItsLaterTailEvents() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentConversation conversation = conversation();
        when(conversationMapper.selectOne(any())).thenReturn(conversation);
        when(messageMapper.selectList(any())).thenReturn(
                List.of(message(10L, "COMPACTION_SUMMARY", "<conversation-checkpoint version=\"3\">durable summary</conversation-checkpoint>")),
                List.of(message(11L, "FINAL", "Tail final outcome")));
        AgentConversationService service = new AgentConversationService(conversationMapper, messageMapper, mock(RagConfig.class));

        String recovered = service.buildMemoryContext(7, 3, "conversation");

        assertTrue(recovered.contains("durable summary"));
        assertTrue(recovered.contains("Tail final outcome"));
        assertTrue(!recovered.contains("obsolete legacy summary"));
    }

    @Test
    void recoveryDoesNotReadStaleConversationSummaryWithoutDurableCompactionEvent() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        when(conversationMapper.selectOne(any())).thenReturn(conversation());
        when(messageMapper.selectList(any())).thenReturn(
                List.of(),
                List.of(message(11L, "FINAL", "Tail final outcome")));
        AgentConversationService service = new AgentConversationService(conversationMapper, messageMapper, mock(RagConfig.class));

        String recovered = service.buildMemoryContext(7, 3, "conversation");

        assertTrue(recovered.contains("Tail final outcome"));
        assertTrue(!recovered.contains("obsolete legacy summary"));
        assertTrue(!recovered.contains("\u5386\u53f2\u6458\u8981:"));
    }

    @Test
    void memoryStatsUseDurableCompactionSummaryInsteadOfLegacyAggregateCache() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentConversation conversation = conversation();
        String durableSummary = "<conversation-checkpoint version=\"3\">durable summary</conversation-checkpoint>";
        when(conversationMapper.selectOne(any())).thenReturn(conversation);
        when(messageMapper.selectList(any())).thenReturn(
                List.of(message(21L, "COMPACTION_SUMMARY", durableSummary)));
        when(messageMapper.selectCount(any())).thenReturn(7L);
        AgentConversationService service = new AgentConversationService(conversationMapper, messageMapper, mock(RagConfig.class));

        AgentConversationService.MemoryStats stats = service.getMemoryStats(7, 3, "conversation");

        assertTrue(stats.getEstimatedTokens() == durableSummary.length());
        assertTrue(stats.getMessageCount() == 7);
        assertTrue(stats.isNeedsCompact());
    }

    @Test
    void compactionSummaryIsPersistedAsAFirstClassConversationEvent() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentConversationService service = new AgentConversationService(conversationMapper, messageMapper, mock(RagConfig.class));
        AgentConversation conversation = conversation();

        service.saveCompactionSummary(conversation, "<conversation-checkpoint version=\"3\">summary</conversation-checkpoint>",
                Map.of("strategy", "model"));

        ArgumentCaptor<AgentMessage> persisted = ArgumentCaptor.forClass(AgentMessage.class);
        verify(messageMapper).insert(persisted.capture());
        assertTrue("COMPACTION_SUMMARY".equals(persisted.getValue().getEventType()));
        assertTrue(persisted.getValue().getContent().contains("version=\"3\""));
        assertTrue(conversation.getSummary().contains("summary"));
        verify(conversationMapper, atLeast(1)).update(any(), any());
    }

    private AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("conversation");
        conversation.setStudentId(7);
        conversation.setProjectId(3);
        conversation.setSummary("obsolete legacy summary");
        return conversation;
    }

    private AgentMessage message(Long id, String eventType, String content) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(id);
        message.setEventType(eventType);
        message.setContent(content);
        return message;
    }


    @Test
    void manualCompactionRejectsAnUnavailableRequestedModelBeforeWritingEvents() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = mock(AgentMessageMapper.class);
        AgentModelConfigService modelConfigService = mock(AgentModelConfigService.class);
        when(conversationMapper.selectOne(any())).thenReturn(conversation());
        when(modelConfigService.resolveForStudent(7, 17)).thenReturn(null);
        AgentConversationService service = new AgentConversationService(conversationMapper, messageMapper,
                mock(RagConfig.class), mock(CompactionAgent.class), modelConfigService);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.compactConversation(7, 3, "conversation", 17));

        assertTrue(error.getMessage().contains("not found or disabled"));
        verify(messageMapper, never()).insert(any());
    }

}
