package com.labex.labexagent.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.mapper.AgentConversationMapper;
import com.labex.rag.config.RagConfig;
import org.junit.jupiter.api.Test;

class AgentConversationMemoryModeTest {

    @Test
    void durableMemoryProjectionIsTheOnlyProviderMemoryPath() {
        AgentConversationMapper conversations = mock(AgentConversationMapper.class);
        AgentConversationMemoryProjectionService durable = mock(AgentConversationMemoryProjectionService.class);
        when(durable.buildContext(7, 3, "conversation")).thenReturn("durable request\ndurable answer");
        AgentConversationService service = new AgentConversationService(
                conversations, mock(RagConfig.class), null, durable, null);

        String context = service.buildMemoryContext(7, 3, "conversation");

        assertThat(context).contains("durable request", "durable answer");
        verify(durable).buildContext(7, 3, "conversation");
        verify(conversations, never()).selectOne(any());
    }
}
