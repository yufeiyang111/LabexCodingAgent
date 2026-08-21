package com.labex.monitor.health;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.monitor.auth.MonitorAccessService;
import com.labex.monitor.auth.MonitorAuthInterceptor;
import com.labex.monitor.config.MonitorProperties;
import com.labex.monitor.controller.MonitorHealthController;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MonitorHealthControllerTest {

    private final MonitorAccessService accessService = mock(MonitorAccessService.class);
    private final MonitorHealthQueryService queryService = mock(MonitorHealthQueryService.class);
    private MonitorProperties properties;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new MonitorProperties();
        properties.setEnabled(true);
        properties.setAccessCode("s3cret");
        ObjectMapper objectMapper = new ObjectMapper();
        MonitorAuthInterceptor interceptor = new MonitorAuthInterceptor(accessService, properties, objectMapper);
        mockMvc = MockMvcBuilders.standaloneSetup(new MonitorHealthController(queryService))
                .addInterceptors(interceptor)
                .build();
    }

    private HealthSnapshot upSnapshot() {
        return new HealthSnapshot(
                HealthStatus.UP.name(),
                Instant.now().toString(),
                12L,
                1,
                1,
                0,
                0,
                List.of(new DependencyHealthStatus("mysql", HealthStatus.UP.name(), Instant.now().toString(),
                        12L, "ok", null, true, java.util.Map.of())));
    }

    @Test
    void unauthenticatedRequestIsRejected() throws Exception {
        when(accessService.isEnabled()).thenReturn(true);
        when(accessService.isValidToken(null)).thenReturn(false);

        mockMvc.perform(get("/ops/health/summary"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void invalidTokenIsRejected() throws Exception {
        when(accessService.isEnabled()).thenReturn(true);
        when(accessService.isValidToken("bad")).thenReturn(false);

        mockMvc.perform(get("/ops/health/summary").header("X-Monitor-Token", "bad"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledMonitorIsServiceUnavailable() throws Exception {
        properties.setAccessCode("");
        when(accessService.isEnabled()).thenReturn(false);

        mockMvc.perform(get("/ops/health/summary"))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void authenticatedSummaryReturnsSnapshot() throws Exception {
        when(accessService.isEnabled()).thenReturn(true);
        when(accessService.isValidToken("good")).thenReturn(true);
        when(queryService.summary()).thenReturn(upSnapshot());

        mockMvc.perform(get("/ops/health/summary").header("X-Monitor-Token", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.overallStatus").value("UP"))
                .andExpect(jsonPath("$.data.dependencies[0].dependencyName").value("mysql"));
    }

    @Test
    void authenticatedDependenciesReturnsFlatList() throws Exception {
        when(accessService.isEnabled()).thenReturn(true);
        when(accessService.isValidToken("good")).thenReturn(true);
        when(queryService.dependencies()).thenReturn(upSnapshot().dependencies());

        mockMvc.perform(get("/ops/health/dependencies").header("X-Monitor-Token", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].dependencyName").value("mysql"))
                .andExpect(jsonPath("$.data[0].status").value("UP"));
    }
}
