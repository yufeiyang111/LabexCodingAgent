package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.labex.entity.AgentConversation;
import com.labex.entity.AgentMessage;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.rag.config.RagConfig;
import java.util.List;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentConversationServicePagingTest {

    @BeforeAll
    static void initializeEntityMetadata() {
        initializeEntityMetadata(AgentConversation.class);
        initializeEntityMetadata(AgentMessage.class);
    }

    @Test
    void loadsOnlyTheLatestRequestedTurnsAndReturnsCursorForOlderHistory() {
        AgentConversationMapper conversationMapper = org.mockito.Mockito.mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = org.mockito.Mockito.mock(AgentMessageMapper.class);
        when(conversationMapper.selectOne(any())).thenReturn(conversation());
        when(messageMapper.selectList(any())).thenReturn(
                List.of(message(50L, "USER"), message(40L, "USER"), message(30L, "USER")),
                List.of(message(40L, "USER"), message(41L, "FINAL"), message(50L, "USER"),
                        message(51L, "FINAL")));
        AgentConversationService service = new AgentConversationService(conversationMapper, messageMapper,
                org.mockito.Mockito.mock(RagConfig.class));

        AgentConversationService.MessagePage page = service.messagePage(7, 3, "conversation", null, 2);

        assertEquals(List.of(40L, 41L, 50L, 51L), page.events().stream().map(AgentMessage::getMessageId).toList());
        assertTrue(page.hasMore());
        assertEquals(40L, page.nextBeforeMessageId());
    }

    @Test
    void listsConversationsUsingDeterministicServerOrdering() {
        AgentConversationMapper conversationMapper = org.mockito.Mockito.mock(AgentConversationMapper.class);
        AgentMessageMapper messageMapper = org.mockito.Mockito.mock(AgentMessageMapper.class);
        when(conversationMapper.selectList(any())).thenReturn(List.of());
        AgentConversationService service = new AgentConversationService(conversationMapper, messageMapper,
                org.mockito.Mockito.mock(RagConfig.class));

        service.list(7, 3);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Wrapper<AgentConversation>> captor = ArgumentCaptor.forClass(Wrapper.class);
        org.mockito.Mockito.verify(conversationMapper).selectList(captor.capture());
        String sql = captor.getValue().getSqlSegment().replaceAll("\s+", " ").toLowerCase();
        assertTrue(sql.contains("order by update_time desc,create_time desc,conversation_id desc"), sql);
    }

    private static void initializeEntityMetadata(Class<?> entityType) {
        if (TableInfoHelper.getTableInfo(entityType) == null) {
            TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), entityType);
        }
    }

    private AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("conversation");
        conversation.setStudentId(7);
        conversation.setProjectId(3);
        conversation.setStatus(1);
        return conversation;
    }

    private AgentMessage message(Long id, String eventType) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(id);
        message.setEventType(eventType);
        message.setContent(eventType + "-" + id);
        return message;
    }
}
