package com.labex.monitor.runtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.monitor.auth.MonitorAccessService;
import com.labex.monitor.auth.MonitorAuthInterceptor;
import com.labex.monitor.config.MonitorProperties;
import com.labex.monitor.controller.MonitorRuntimeController;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MonitorRuntimeControllerTest {

    private final MonitorAccessService accessService = mock(MonitorAccessService.class);
    private final AgentRuntimeQueryService queryService = mock(AgentRuntimeQueryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MonitorProperties properties = new MonitorProperties();
        properties.setEnabled(true);
        properties.setAccessCode("s3cret");
        MonitorAuthInterceptor interceptor =
                new MonitorAuthInterceptor(accessService, properties, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(
                        new MonitorRuntimeController(queryService, new MonitorRuntimeProperties()))
                .addInterceptors(interceptor)
                .build();
    }

    private void allowAccess(String token) {
        when(accessService.isEnabled()).thenReturn(true);
        when(accessService.isValidToken(token)).thenReturn(true);
    }

    @Test
    void unauthenticatedListIsRejected() throws Exception {
        when(accessService.isEnabled()).thenReturn(true);
        when(accessService.isValidToken(null)).thenReturn(false);

        mockMvc.perform(get("/ops/runtime/tasks"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        when(accessService.isEnabled()).thenReturn(true);
        when(accessService.isValidToken("bad")).thenReturn(false);

        mockMvc.perform(get("/ops/runtime/tasks").header("X-Monitor-Token", "bad"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void taskListReturnsPage() throws Exception {
        allowAccess("good");
        AgentRuntimePage<AgentRuntimeTaskSummary> page = new AgentRuntimePage<>(
                List.of(new AgentRuntimeTaskSummary(1L, "conv-1", "s1", "build", "running", null,
                        null, null, null, null, null, null, null, null, null, null, null, null, null, false)),
                1, 20, 1, false);
        when(queryService.listTasks(org.mockito.ArgumentMatchers.any())).thenReturn(page);

        mockMvc.perform(get("/ops/runtime/tasks").header("X-Monitor-Token", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].taskId").value(1))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void invalidStatusIsBadRequest() throws Exception {
        allowAccess("good");

        mockMvc.perform(get("/ops/runtime/tasks").header("X-Monitor-Token", "good")
                        .param("status", "not-a-status"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativePageIsBadRequest() throws Exception {
        allowAccess("good");

        mockMvc.perform(get("/ops/runtime/tasks").header("X-Monitor-Token", "good")
                        .param("page", "-1"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void negativeStudentIdIsBadRequest() throws Exception {
        allowAccess("good");

        mockMvc.perform(get("/ops/runtime/tasks").header("X-Monitor-Token", "good")
                        .param("studentId", "-5"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void taskDetailNotFoundIsError() throws Exception {
        allowAccess("good");
        when(queryService.taskDetail(999L)).thenReturn(null);

        mockMvc.perform(get("/ops/runtime/tasks/999").header("X-Monitor-Token", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void taskDetailReturnsDetail() throws Exception {
        allowAccess("good");
        AgentRuntimeTaskDetail detail = new AgentRuntimeTaskDetail(1L, "conv-1", "s1", "build",
                "running", null, null, null, null, null, null, null, null, null, null, null,
                "deepseek", "deepseek-chat", null, null, "tool_executed", null, null, null, null);
        when(queryService.taskDetail(1L)).thenReturn(detail);

        mockMvc.perform(get("/ops/runtime/tasks/1").header("X-Monitor-Token", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.taskId").value(1))
                .andExpect(jsonPath("$.data.provider").value("deepseek"));
    }

    @Test
    void timelineReturnsEvents() throws Exception {
        allowAccess("good");
        AgentRuntimePage<AgentRuntimeEventItem> page = new AgentRuntimePage<>(
                List.of(new AgentRuntimeEventItem(1L, 1L, "task_initialized", "running", null, null)),
                0, 50, 1, false);
        when(queryService.taskTimeline(1L, null, 0)).thenReturn(page);

        mockMvc.perform(get("/ops/runtime/tasks/1/timeline").header("X-Monitor-Token", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].eventType").value("task_initialized"));
    }

    @Test
    void timelineWithNegativeAfterSequenceIsBadRequest() throws Exception {
        allowAccess("good");

        mockMvc.perform(get("/ops/runtime/tasks/1/timeline").header("X-Monitor-Token", "good")
                        .param("afterSequence", "-1"))
                .andExpect(status().isBadRequest());
    }
}
