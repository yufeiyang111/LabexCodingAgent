package com.labex.monitor.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.OpsMetricSample;
import com.labex.mapper.AgentTaskMapper;
import com.labex.mapper.AgentTokenUsageMapper;
import com.labex.mapper.OpsMetricSampleMapper;
import com.labex.monitor.system.SystemMetricsService;
import com.labex.monitor.system.SystemSnapshot;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class MetricSamplingServiceTest {

    private final SystemMetricsService systemMetrics = mock(SystemMetricsService.class);
    private final AgentTaskMapper taskMapper = mock(AgentTaskMapper.class);
    private final AgentTokenUsageMapper tokenUsageMapper = mock(AgentTokenUsageMapper.class);
    private final OpsMetricSampleMapper sampleMapper = mock(OpsMetricSampleMapper.class);
    private final OpsMetricProperties properties = new OpsMetricProperties();
    private MetricSamplingService service;

    @BeforeEach
    void setUp() {
        service = new MetricSamplingService(systemMetrics, taskMapper, tokenUsageMapper, sampleMapper, properties);
    }

    private SystemSnapshot snapshot() {
        SystemSnapshot snapshot = new SystemSnapshot();
        snapshot.setCpuPercent(12.5);
        snapshot.setMemoryPercent(45.0);
        snapshot.setMemoryUsedBytes(1024L);
        snapshot.setDiskPercent(33.0);
        snapshot.setDiskUsedBytes(2048L);
        snapshot.setHeapUsedBytes(512L);
        snapshot.setHeapMaxBytes(2048L);
        snapshot.setSystemLoadAverage(0.75);
        return snapshot;
    }

    @Test
    void samplePersistsSystemTaskAndTokenMetrics() {
        when(systemMetrics.snapshot()).thenReturn(snapshot());
        when(taskMapper.selectStatusCounts()).thenReturn(List.of(
                Map.of("status_value", "running", "count_value", 3L),
                Map.of("status_value", "waiting_approval", "count_value", 2L),
                Map.of("status_value", "completed", "count_value", 10L),
                Map.of("status_value", "failed", "count_value", 1L),
                Map.of("status_value", "cancelled", "count_value", 1L)));
        when(tokenUsageMapper.selectWindowTotals(any())).thenReturn(Map.of(
                "prompt_tokens", 100L, "completion_tokens", 50L, "total_tokens", 150L));

        service.sample();

        ArgumentCaptor<OpsMetricSample> captor = ArgumentCaptor.forClass(OpsMetricSample.class);
        verify(sampleMapper).insert(captor.capture());
        OpsMetricSample sample = captor.getValue();
        assertThat(sample.getSampleTime()).isNotNull();
        assertThat(sample.getCpuPercent()).isEqualTo(12.5);
        assertThat(sample.getMemoryPercent()).isEqualTo(45.0);
        assertThat(sample.getTaskTotal()).isEqualTo(17);
        assertThat(sample.getTaskRunning()).isEqualTo(3);
        assertThat(sample.getTaskWaiting()).isEqualTo(2);
        assertThat(sample.getTaskCompleted()).isEqualTo(10);
        assertThat(sample.getTaskFailed()).isEqualTo(1);
        assertThat(sample.getTaskCancelled()).isEqualTo(1);
        assertThat(sample.getTokenTotal()).isEqualTo(150L);
        assertThat(sample.getTokenPromptTotal()).isEqualTo(100L);
    }

    @Test
    void sampleIsIdempotentWhenSameMinuteAlreadySampled() {
        when(systemMetrics.snapshot()).thenReturn(snapshot());
        when(taskMapper.selectStatusCounts()).thenReturn(List.of());
        when(tokenUsageMapper.selectWindowTotals(any())).thenReturn(Map.of());
        when(sampleMapper.insert(any())).thenThrow(new DuplicateKeyException("duplicate sample_time"));

        service.sample();

        verify(sampleMapper).insert(any());
    }

    @Test
    void systemCollectionFailureIsIsolatedButSampleStillPersists() {
        when(systemMetrics.snapshot()).thenThrow(new IllegalStateException("boom"));
        when(taskMapper.selectStatusCounts()).thenReturn(List.of());
        when(tokenUsageMapper.selectWindowTotals(any())).thenReturn(Map.of());

        service.sample();

        ArgumentCaptor<OpsMetricSample> captor = ArgumentCaptor.forClass(OpsMetricSample.class);
        verify(sampleMapper).insert(captor.capture());
        assertThat(captor.getValue().getCpuPercent()).isNull();
        assertThat(captor.getValue().getTaskTotal()).isZero();
    }

    @Test
    void taskCollectionFailureIsIsolated() {
        when(systemMetrics.snapshot()).thenReturn(snapshot());
        when(taskMapper.selectStatusCounts()).thenThrow(new IllegalStateException("db down"));
        when(tokenUsageMapper.selectWindowTotals(any())).thenReturn(Map.of());

        service.sample();

        ArgumentCaptor<OpsMetricSample> captor = ArgumentCaptor.forClass(OpsMetricSample.class);
        verify(sampleMapper).insert(captor.capture());
        assertThat(captor.getValue().getTaskTotal()).isNull();
        assertThat(captor.getValue().getCpuPercent()).isEqualTo(12.5);
    }

    @Test
    void scheduledSampleSwallowsInsertFailure() {
        when(systemMetrics.snapshot()).thenReturn(snapshot());
        when(taskMapper.selectStatusCounts()).thenReturn(List.of());
        when(tokenUsageMapper.selectWindowTotals(any())).thenReturn(Map.of());
        when(sampleMapper.insert(any())).thenThrow(new IllegalStateException("db down"));

        service.sampleScheduled();

        verify(sampleMapper).insert(any());
    }

    @Test
    void sampleTimeIsMinuteAligned() {
        when(systemMetrics.snapshot()).thenReturn(snapshot());
        when(taskMapper.selectStatusCounts()).thenReturn(List.of());
        when(tokenUsageMapper.selectWindowTotals(any())).thenReturn(Map.of());

        service.sample();

        ArgumentCaptor<OpsMetricSample> captor = ArgumentCaptor.forClass(OpsMetricSample.class);
        verify(sampleMapper).insert(captor.capture());
        LocalDateTime sampleTime = captor.getValue().getSampleTime();
        assertThat(sampleTime.getSecond()).isZero();
        assertThat(sampleTime.getNano()).isZero();
    }
}
