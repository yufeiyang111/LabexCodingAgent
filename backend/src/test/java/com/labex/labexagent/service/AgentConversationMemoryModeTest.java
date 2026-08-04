package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.mapper.AgentTaskMapper;
import com.labex.rag.config.RagConfig;
import org.junit.jupiter.api.Test;

class AgentConversationMemoryModeTest {

    @Test
    void removedLegacyModeCannotOverrideTheDurableMemoryProjection() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentMessageMapper legacyMessages = mock(AgentMessageMapper.class);
        AgentConversationMemoryProjectionService durable = mock(AgentConversationMemoryProjectionService.class);
        when(durable.buildContext(7, 3, "conversation")).thenReturn("durable request\ndurable answer");
        AgentConversationService service = new AgentConversationService(
                conversations, legacyMessages, mock(RagConfig.class), mock(AgentTaskMapper.class), durable, "legacy");

        String context = service.buildMemoryContext(7, 3, "conversation");

        assertThat(context).contains("durable request", "durable answer");
        verify(legacyMessages, never()).selectList(any());
        verify(conversations, never()).selectOne(any());
    }

    @Test
    void removedShadowModeAlsoReturnsOnlyTheDurableProjection() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentMessageMapper legacyMessages = mock(AgentMessageMapper.class);
        AgentConversationMemoryProjectionService durable = mock(AgentConversationMemoryProjectionService.class);
        when(durable.buildContext(7, 3, "conversation")).thenReturn("durable only");
        AgentConversationService service = new AgentConversationService(
                conversations, legacyMessages, mock(RagConfig.class), mock(AgentTaskMapper.class), durable, "shadow");

        assertThat(service.buildMemoryContext(7, 3, "conversation")).isEqualTo("durable only");
        verify(legacyMessages, never()).selectList(any());
    }
}
