package com.labex.monitor.metric;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.labex.monitor.auth.MonitorAccessService;
import com.labex.monitor.auth.MonitorAuthInterceptor;
import com.labex.monitor.config.MonitorProperties;
import com.labex.monitor.controller.MonitorMetricController;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MonitorMetricControllerTest {

    private final MonitorAccessService accessService = mock(MonitorAccessService.class);
    private final MetricQueryService queryService = mock(MetricQueryService.class);
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        MonitorProperties properties = new MonitorProperties();
        properties.setEnabled(true);
        properties.setAccessCode("s3cret");
        MonitorAuthInterceptor interceptor =
                new MonitorAuthInterceptor(accessService, properties, new ObjectMapper());
        mockMvc = MockMvcBuilders.standaloneSetup(new MonitorMetricController(queryService))
                .addInterceptors(interceptor)
                .build();
    }

    private void allowAccess(String token) {
        when(accessService.isEnabled()).thenReturn(true);
        when(accessService.isValidToken(token)).thenReturn(true);
    }

    @Test
    void unauthenticatedOverviewIsRejected() throws Exception {
        when(accessService.isEnabled()).thenReturn(true);
        when(accessService.isValidToken(null)).thenReturn(false);

        mockMvc.perform(get("/ops/metrics/overview"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void overviewReturnsLatestSample() throws Exception {
        allowAccess("good");
        MetricOverview overview = new MetricOverview("2026-08-21T12:00:00", 10.0, 40.0, 30.0,
                512.0, 2048.0, 0.5, 5, 2, 2, 0, 1, 0, 100L, 60L, 40L);
        when(queryService.overview()).thenReturn(overview);

        mockMvc.perform(get("/ops/metrics/overview").header("X-Monitor-Token", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.cpuPercent").value(10.0))
                .andExpect(jsonPath("$.data.taskFailed").value(1));
    }

    @Test
    void overviewEmptyIsError() throws Exception {
        allowAccess("good");
        when(queryService.overview()).thenReturn(null);

        mockMvc.perform(get("/ops/metrics/overview").header("X-Monitor-Token", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(404));
    }

    @Test
    void timeseriesReturnsPoints() throws Exception {
        allowAccess("good");
        MetricPoint point = new MetricPoint("2026-08-21T12:00:00", 10.0, 40.0, 30.0,
                512.0, 2048.0, 0.5, 5, 2, 1, 100L);
        when(queryService.timeseries("24h")).thenReturn(List.of(point));

        mockMvc.perform(get("/ops/metrics/timeseries").header("X-Monitor-Token", "good"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data[0].cpuPercent").value(10.0));
    }

    @Test
    void timeseriesInvalidRangeIsBadRequest() throws Exception {
        allowAccess("good");

        mockMvc.perform(get("/ops/metrics/timeseries").header("X-Monitor-Token", "good")
                        .param("range", "99d"))
                .andExpect(status().isBadRequest());
    }
}
