package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.labex.entity.AgentConversation;
import com.labex.mapper.AgentConversationMapper;
import com.labex.rag.config.RagConfig;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentConversationServicePagingTest {

    @BeforeAll
    static void initializeEntityMetadata() {
        if (TableInfoHelper.getTableInfo(AgentConversation.class) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), AgentConversation.class);
        }
    }

    @Test
    void delegatesHistoryPagingToTheDurableTaskProjector() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentConversationHistoryProjectionService history = mock(AgentConversationHistoryProjectionService.class);
        AgentConversationHistoryProjectionService.HistoryPage expected =
                new AgentConversationHistoryProjectionService.HistoryPage(
                        "durable-task-history-v1", "conversation", List.of(), true, 40L, false);
        when(history.page(7, 3, "conversation", 50L, 2)).thenReturn(expected);
        AgentConversationService service = new AgentConversationService(conversations, mock(RagConfig.class),
                null, mock(AgentConversationMemoryProjectionService.class), history);

        AgentConversationHistoryProjectionService.HistoryPage actual =
                service.messagePage(7, 3, "conversation", 50L, 2);

        assertSame(expected, actual);
        verify(history).page(7, 3, "conversation", 50L, 2);
    }

    @Test
    void listsConversationsUsingDeterministicServerOrdering() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        when(conversationMapper.selectList(any())).thenReturn(List.of());
        AgentConversationService service = new AgentConversationService(conversationMapper, mock(RagConfig.class),
                null, mock(AgentConversationMemoryProjectionService.class),
                mock(AgentConversationHistoryProjectionService.class));

        service.list(7, 3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AgentConversation>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(conversationMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment().replaceAll("\\s+", " ").toLowerCase();
        assertTrue(sql.contains("order by update_time desc,create_time desc,conversation_id desc"), sql);
        assertTrue(sql.contains("mode") && (sql.contains("<>") || sql.contains("!=")), sql);
    }
}
