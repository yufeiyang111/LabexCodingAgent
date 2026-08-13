package com.labex.labexagent.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.labex.labexagent.diff.DiffService;
import com.labex.labexagent.dto.AgentStreamHttpRequest;
import com.labex.labexagent.dto.AgentStreamRequest;
import com.labex.labexagent.permission.PermissionService;
import com.labex.labexagent.runtime.AgentCancellationRegistry;
import com.labex.labexagent.runtime.AgentLoopEngine;
import com.labex.labexagent.service.AgentCommandService;
import com.labex.labexagent.service.AgentConversationService;
import com.labex.labexagent.service.AgentInteractionService;
import com.labex.labexagent.service.AgentTaskService;
import com.labex.labexagent.service.TokenTracker;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class StudentAgentControllerStreamSecurityTest {

    /** 模拟 Spring Boot 默认 ObjectMapper：全局忽略未知字段，只有 DTO 级契约可以拒绝它们。 */
    private static final ObjectMapper APP_STYLE_JSON = JsonMapper.builder()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build()
            .findAndRegisterModules();

    @Test
    void rejectsRecoveryFieldsBeforeAnyServiceDispatch() throws Exception {
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentCommandService commands = mock(AgentCommandService.class);
        MockMvc mockMvc = mockMvc(engine, commands);

        mockMvc.perform(post("/student/projects/12/agent/stream")
                        .with(authenticatedAs(7))
                        .contentType("application/json")
                        .content("""
                                {"sessionId":"session-1","conversationId":"conversation-1","message":"hello",
                                 "resumeTaskId":71,"resumeInteractionId":"interaction-71"}
                                """))
                .andExpect(status().is4xxClientError());

        verify(engine, never()).start(anyInt(), anyInt(), any(AgentStreamRequest.class));
        verify(commands, never()).prepareAgentStreamRequest(anyInt(), anyInt(), any(AgentStreamRequest.class));
    }

    @Test
    void publicStreamBodyCannotInjectRecoveryFields() {
        assertThatThrownBy(() -> APP_STYLE_JSON.readValue("""
                {"sessionId":"session-1","message":"hello","resumeTaskId":71}
                """, AgentStreamHttpRequest.class))
                .isInstanceOf(JsonProcessingException.class);
        assertThatThrownBy(() -> APP_STYLE_JSON.readValue("""
                {"sessionId":"session-1","message":"hello","resumeInteractionId":"interaction-71"}
                """, AgentStreamHttpRequest.class))
                .isInstanceOf(JsonProcessingException.class);
    }

    @Test
    void internalResumeNoteIsNotBindableFromThePublicBody() throws Exception {
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        AgentCommandService commands = mock(AgentCommandService.class);
        MockMvc mockMvc = mockMvc(engine, commands);

        mockMvc.perform(post("/student/projects/12/agent/stream")
                        .with(authenticatedAs(7))
                        .contentType("application/json")
                        .content("""
                                {"sessionId":"session-1","message":"hello","resumeNote":"internal scheduler note"}
                                """))
                .andExpect(status().is4xxClientError());

        verify(engine, never()).start(anyInt(), anyInt(), any(AgentStreamRequest.class));
        verify(commands, never()).prepareAgentStreamRequest(anyInt(), anyInt(), any(AgentStreamRequest.class));
    }

    @Test
    void normalUserMessageStillDispatchesAsANewRun() throws Exception {
        AgentLoopEngine engine = mock(AgentLoopEngine.class);
        when(engine.start(anyInt(), anyInt(), any(AgentStreamRequest.class))).thenReturn(new SseEmitter());
        MockMvc mockMvc = mockMvc(engine, mock(AgentCommandService.class));

        mockMvc.perform(post("/student/projects/12/agent/stream")
                        .with(authenticatedAs(7))
                        .contentType("application/json")
                        .content("""
                                {"sessionId":"session-1","conversationId":"conversation-1","mode":"agent",
                                 "message":"hello","displayMessage":"hello","activePath":"README.md",
                                 "modelConfigId":19,"backgroundRun":false,"submittedAt":"2026-08-11T10:00:00"}
                                """))
                .andExpect(status().is2xxSuccessful());

        verify(engine).start(eq(7), eq(12),
                org.mockito.ArgumentMatchers.argThat(bound ->
                        bound.getResumeTaskId() == null
                                && bound.getResumeInteractionId() == null
                                && bound.getResumeNote() == null
                                && "README.md".equals(bound.getActivePath())
                                && Integer.valueOf(19).equals(bound.getModelConfigId())));
    }

    @Test
    void convertedInternalRequestNeverCarriesRecoveryIdentifiers() throws Exception {
        AgentStreamHttpRequest http = APP_STYLE_JSON.readValue("""
                {"sessionId":"session-1","conversationId":"conversation-1","message":"hello","modelConfigId":19}
                """, AgentStreamHttpRequest.class);

        AgentStreamRequest internal = http.toInternalRequest();

        assertThat(internal.getSessionId()).isEqualTo("session-1");
        assertThat(internal.getConversationId()).isEqualTo("conversation-1");
        assertThat(internal.getMessage()).isEqualTo("hello");
        assertThat(internal.getModelConfigId()).isEqualTo(19);
        assertThat(internal.getResumeTaskId()).isNull();
        assertThat(internal.getResumeInteractionId()).isNull();
        assertThat(internal.getResumeNote()).isNull();
    }

    private MockMvc mockMvc(AgentLoopEngine engine, AgentCommandService commands) {
        StudentAgentController controller = new StudentAgentController(
                engine, mock(AgentCancellationRegistry.class), mock(DiffService.class), commands,
                mock(AgentConversationService.class), mock(AgentTaskService.class), mock(TokenTracker.class),
                mock(PermissionService.class), mock(AgentInteractionService.class));
        return MockMvcBuilders.standaloneSetup(controller)
                .setMessageConverters(new MappingJackson2HttpMessageConverter(APP_STYLE_JSON))
                .build();
    }

    private org.springframework.test.web.servlet.request.RequestPostProcessor authenticatedAs(int studentId) {
        return request -> {
            request.setUserPrincipal(new UsernamePasswordAuthenticationToken(String.valueOf(studentId), null));
            return request;
        };
    }
}
