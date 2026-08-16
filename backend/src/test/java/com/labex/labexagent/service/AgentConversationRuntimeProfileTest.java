package com.labex.labexagent.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.AgentConversation;
import com.labex.entity.AgentModelConfig;
import com.labex.entity.StudentProject;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfile;
import com.labex.labexagent.runtime.profile.AgentRuntimeProfileProperties;
import com.labex.mapper.AgentConversationMapper;
import com.labex.rag.config.RagConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentConversationRuntimeProfileTest {

    @Test
    void newConversationPersistsTheExplicitNativeRuntimeProfile() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        when(conversationMapper.insert(any())).thenReturn(1);
        AgentConversationService service = new AgentConversationService(
                conversationMapper, mock(RagConfig.class), null, null, null);
        StudentProject project = new StudentProject();
        project.setProjectId(11);
        AgentModelConfig modelConfig = new AgentModelConfig();
        modelConfig.setProvider("openai_compatible");
        modelConfig.setModelName("model-a");

        service.ensureConversation(42, project, null, "build", "implement runtime profile",
                modelConfig, AgentRuntimeProfile.LABEX_NATIVE);

        ArgumentCaptor<AgentConversation> captured = ArgumentCaptor.forClass(AgentConversation.class);
        verify(conversationMapper).insert(captured.capture());
        assertEquals("labex-native", captured.getValue().getRuntimeProfile());
    }

    @Test
    void newConversationUsesConfiguredDefaultProfileWhenClientDoesNotChooseOne() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        when(conversationMapper.insert(any())).thenReturn(1);
        AgentConversationService service = new AgentConversationService(
                conversationMapper, mock(RagConfig.class), null, null, null);
        AgentRuntimeProfileProperties runtimeProfileProperties = new AgentRuntimeProfileProperties();
        runtimeProfileProperties.setDefaultProfile("labex-native");
        service.setRuntimeProfileProperties(runtimeProfileProperties);
        StudentProject project = new StudentProject();
        project.setProjectId(11);
        AgentModelConfig modelConfig = new AgentModelConfig();
        modelConfig.setProvider("openai_compatible");
        modelConfig.setModelName("model-a");

        service.ensureConversation(42, project, null, "build", "implement runtime profile",
                modelConfig, null);

        ArgumentCaptor<AgentConversation> captured = ArgumentCaptor.forClass(AgentConversation.class);
        verify(conversationMapper).insert(captured.capture());
        assertEquals("labex-native", captured.getValue().getRuntimeProfile());
    }
    @Test
    void forkedConversationKeepsTheSourceRuntimeProfile() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        when(conversationMapper.insert(any())).thenReturn(1);
        AgentConversation source = new AgentConversation();
        source.setConversationId("source-conversation");
        source.setStudentId(42);
        source.setProjectId(11);
        source.setTitle("Native conversation");
        source.setRuntimeProfile("labex-native");
        when(conversationMapper.selectOwnedForUpdate(42, 11, "source-conversation")).thenReturn(source);
        AgentConversationService service = new AgentConversationService(
                conversationMapper, mock(RagConfig.class), null, null, null);

        service.forkConversation(42, 11, "source-conversation", null, null);

        ArgumentCaptor<AgentConversation> captured = ArgumentCaptor.forClass(AgentConversation.class);
        verify(conversationMapper).insert(captured.capture());
        assertEquals("labex-native", captured.getValue().getRuntimeProfile());
    }
    @Test
    void existingConversationRejectsAnExplicitProfileChange() {
        AgentConversationMapper conversationMapper = mock(AgentConversationMapper.class);
        AgentConversation existing = new AgentConversation();
        existing.setConversationId("conversation-1");
        existing.setStudentId(42);
        existing.setProjectId(11);
        existing.setStatus(1);
        existing.setRuntimeProfile("labex-legacy");
        when(conversationMapper.selectOne(any())).thenReturn(existing);
        AgentConversationService service = new AgentConversationService(
                conversationMapper, mock(RagConfig.class), null, null, null);
        StudentProject project = new StudentProject();
        project.setProjectId(11);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.ensureConversation(42, project, "conversation-1", "build", "continue work",
                        null, AgentRuntimeProfile.LABEX_NATIVE));

        assertEquals("Conversation runtime profile is immutable: labex-legacy", error.getMessage());
    }
}
