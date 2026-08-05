package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.labex.entity.AgentConversation;
import com.labex.mapper.AgentConversationMapper;
import com.labex.rag.config.RagConfig;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class AgentConversationServiceCompactionTest {

    @BeforeAll
    static void initializeEntityMetadata() {
        if (TableInfoHelper.getTableInfo(AgentConversation.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentConversation.class);
        }
    }

    @Test
    void memoryContextAlwaysUsesTheDurableConversationProjector() {
        AgentConversationMemoryProjectionService memory = mock(AgentConversationMemoryProjectionService.class);
        when(memory.buildContext(7, 3, "conversation")).thenReturn("durable summary\nTail final outcome");
        AgentConversationService service = new AgentConversationService(
                mock(AgentConversationMapper.class), mock(RagConfig.class), null, memory, null);

        String recovered = service.buildMemoryContext(7, 3, "conversation");

        assertTrue(recovered.contains("durable summary"));
        assertTrue(recovered.contains("Tail final outcome"));
    }

    @Test
    void memoryStatsDelegateToTheDurableHistoryProjector() {
        AgentConversationHistoryProjectionService history = mock(AgentConversationHistoryProjectionService.class);
        when(history.memoryStats(7, 3, "conversation")).thenReturn(
                new AgentConversationHistoryProjectionService.MemoryStats(321, 4, true, 12_000));
        AgentConversationService service = new AgentConversationService(mock(AgentConversationMapper.class),
                mock(RagConfig.class), null, mock(AgentConversationMemoryProjectionService.class), history);

        AgentConversationService.MemoryStats stats = service.getMemoryStats(7, 3, "conversation");

        assertEquals(321, stats.getEstimatedTokens());
        assertEquals(4, stats.getMessageCount());
        assertEquals(12_000, stats.getMaxTokens());
        assertTrue(stats.isNeedsCompact());
    }

    @Test
    void compactionOnlyUpdatesConversationMetadata() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentConversation conversation = conversation();
        AgentConversationService service = new AgentConversationService(
                conversations, mock(RagConfig.class), null, null, null);

        service.markCompacted(conversation);

        assertTrue(conversation.getCompactedAt() != null);
        verify(conversations).update(any(), any());
    }

    private AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("conversation");
        conversation.setStudentId(7);
        conversation.setProjectId(3);
        conversation.setSummary("obsolete legacy summary");
        return conversation;
    }
}
