package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentConversation;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.StudentProject;
import com.labex.mapper.AgentConversationMapper;
import com.labex.mapper.AgentMessageMapper;
import com.labex.rag.config.RagConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentConversationUserModelMetadataTest {

    @Test
    void newMainAgentConversationUsesTheSelectedUserModelInsteadOfRagDefaults() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        when(conversationMapper.insert(any())).thenReturn(1);
        AgentConversationService service = new AgentConversationService(
                conversationMapper, mock(AgentMessageMapper.class), mock(RagConfig.class));
        StudentProject project = new StudentProject();
        project.setProjectId(11);
        AgentModelConfig config = new AgentModelConfig();
        config.setProvider("openai_compatible");
        config.setModelName("user-vision-model");

        service.ensureConversation(42, project, null, "agent", "inspect image", config);

        ArgumentCaptor<AgentConversation> captured = ArgumentCaptor.forClass(AgentConversation.class);
        verify(conversationMapper).insert(captured.capture());
        assertEquals("openai_compatible", captured.getValue().getProvider());
        assertEquals("user-vision-model", captured.getValue().getModel());
        assertEquals("durable-v1", captured.getValue().getHistoryProjectionVersion());
    }
}
