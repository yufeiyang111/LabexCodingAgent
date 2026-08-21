package com.labex.monitor.controller;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.monitor.alert.AlertQueryService;
import com.labex.monitor.alert.AlertStateService;
import com.labex.monitor.auth.MonitorAccessService;
import com.labex.monitor.auth.MonitorAuthInterceptor;
import com.labex.monitor.auth.MonitorRoleService;
import com.labex.monitor.config.MonitorProperties;
import com.labex.monitor.dto.AlertDto;
import com.labex.monitor.operation.OperationAuthorizationService;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MonitorAlertControllerTest {

    private final MonitorAccessService accessService = mock(MonitorAccessService.class);
    private final AlertQueryService queryService = mock(AlertQueryService.class);
    private final AlertStateService stateService = mock(AlertStateService.class);
    private final MonitorRoleService roleService = new MonitorRoleService();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MonitorProperties properties = new MonitorProperties();
        properties.setEnabled(true);
        properties.setAccessCode("s3cret");
        MonitorAuthInterceptor interceptor =
                new MonitorAuthInterceptor(accessService, properties, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new MonitorAlertController(queryService, stateService,
                        new OperationAuthorizationService(roleService, accessService), roleService))
                .addInterceptors(interceptor)
                .build();
        when(accessService.isEnabled()).thenReturn(true);
        when(accessService.isValidToken("viewer")).thenReturn(true);
        when(accessService.isValidToken("operator")).thenReturn(true);
        when(accessService.sessionRole("viewer")).thenReturn(MonitorRoleService.VIEWER);
        when(accessService.sessionRole("operator")).thenReturn(MonitorRoleService.OPERATOR);
    }

    @Test
    void unauthenticatedListIsRejected() throws Exception {
        when(accessService.isValidToken(null)).thenReturn(false);

        mockMvc.perform(get("/ops/alerts")).andExpect(status().isUnauthorized());
    }

    @Test
    void viewerCanListAlerts() throws Exception {
        AlertDto dto = new AlertDto();
        dto.setAlertId(1L);
        dto.setStatus("FIRING");
        dto.setSeverity("warning");
        when(queryService.query(1, 20, null, null)).thenReturn(List.of(dto));
        when(queryService.count(null, null)).thenReturn(1L);

        mockMvc.perform(get("/ops/alerts").header("X-Monitor-Token", "viewer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void viewerCannotAcknowledge() throws Exception {
        mockMvc.perform(post("/ops/alerts/1/acknowledge").header("X-Monitor-Token", "viewer"))
                .andExpect(status().isForbidden());
    }

    @Test
    void operatorCanAcknowledge() throws Exception {
        when(stateService.acknowledge(1L, "OPS_OPERATOR")).thenReturn(true);

        mockMvc.perform(post("/ops/alerts/1/acknowledge").header("X-Monitor-Token", "operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void acknowledgeMissingAlertReturnsNotFound() throws Exception {
        when(stateService.acknowledge(99L, "OPS_OPERATOR")).thenReturn(false);

        mockMvc.perform(post("/ops/alerts/99/acknowledge").header("X-Monitor-Token", "operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void silenceRejectsInvalidDuration() throws Exception {
        when(stateService.silence(anyLong(), anyInt(), anyString()))
                .thenThrow(new IllegalArgumentException("静默时长必须为 1-10080 分钟"));

        mockMvc.perform(post("/ops/alerts/1/silence").header("X-Monitor-Token", "operator")
                        .contentType("application/json")
                        .content("{\"durationMinutes\": 0}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void invalidStatusFilterIsBadRequest() throws Exception {
        when(queryService.query(1, 20, "BOGUS", null))
                .thenThrow(new IllegalArgumentException("不支持的告警状态: BOGUS"));

        mockMvc.perform(get("/ops/alerts").header("X-Monitor-Token", "viewer").param("status", "BOGUS"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resolveUnknownAlertReturnsNotFound() throws Exception {
        when(stateService.resolve(99L, "OPS_OPERATOR", null)).thenReturn(false);

        mockMvc.perform(post("/ops/alerts/99/resolve").header("X-Monitor-Token", "operator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }
}