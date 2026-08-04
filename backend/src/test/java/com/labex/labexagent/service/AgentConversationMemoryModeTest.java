package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentConversation;
import com.labex.entity.AgentMessage;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.rag.config.RagConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AgentConversationMemoryModeTest {

    @Test
    void durableModeDoesNotReadLegacyAgentMessages() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentMessageMapper messages = mock(AgentMessageMapper.class);
        AgentConversationMemoryProjectionService durable = mock(AgentConversationMemoryProjectionService.class);
        when(durable.buildContext(7, 3, "conversation"))
                .thenReturn("durable request\ndurable answer");
        AgentConversationService service = new AgentConversationService(
                conversations, messages, mock(RagConfig.class), mock(AgentTaskMapper.class), durable, "durable");

        String context = service.buildMemoryContext(7, 3, "conversation");

        assertThat(context).contains("durable request", "durable answer");
        verify(messages, never()).selectList(any());
        verify(conversations, never()).selectOne(any());
    }

    @Test
    void shadowModeBuildsBothPathsButReturnsLegacyUntilTheGateIsSwitched() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentMessageMapper messages = mock(AgentMessageMapper.class);
        AgentConversationMemoryProjectionService durable = mock(AgentConversationMemoryProjectionService.class);
        when(conversations.selectOne(any())).thenReturn(conversation());
        when(messages.selectList(any())).thenReturn(
                List.of(),
                List.of(message(9L, "FINAL", "legacy answer")));
        when(durable.buildContext(7, 3, "conversation"))
                .thenReturn("durable answer");
        AgentConversationService service = new AgentConversationService(
                conversations, messages, mock(RagConfig.class), mock(AgentTaskMapper.class), durable, "shadow");

        String context = service.buildMemoryContext(7, 3, "conversation");

        assertThat(context).contains("legacy answer").doesNotContain("durable answer");
        verify(durable).buildContext(7, 3, "conversation");
        verify(messages, org.mockito.Mockito.atLeastOnce()).selectList(any());
    }

    private AgentConversation conversation() {
        AgentConversation conversation = new AgentConversation();
        conversation.setConversationId("conversation");
        conversation.setStudentId(7);
        conversation.setProjectId(3);
        conversation.setStatus(1);
        return conversation;
    }

    private AgentMessage message(long id, String type, String content) {
        AgentMessage message = new AgentMessage();
        message.setMessageId(id);
        message.setEventType(type);
        message.setContent(content);
        return message;
    }
}
