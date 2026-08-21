package com.labex.monitor.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.OpsMetricSample;
import com.labex.mapper.OpsMetricSampleMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricQueryServiceTest {

    private final OpsMetricSampleMapper sampleMapper = mock(OpsMetricSampleMapper.class);
    private final MetricQueryService service = new MetricQueryService(sampleMapper);

    private OpsMetricSample sample(LocalDateTime time) {
        OpsMetricSample sample = new OpsMetricSample();
        sample.setSampleTime(time);
        sample.setCpuPercent(10.0);
        sample.setMemoryPercent(40.0);
        sample.setTaskTotal(5);
        sample.setTaskRunning(2);
        sample.setTaskFailed(1);
        sample.setTokenTotal(100L);
        return sample;
    }

    @Test
    void overviewReturnsLatestSample() {
        OpsMetricSample latest = sample(LocalDateTime.of(2026, 8, 21, 12, 0));
        when(sampleMapper.selectLatest()).thenReturn(latest);

        MetricOverview overview = service.overview();

        assertThat(overview).isNotNull();
        assertThat(overview.cpuPercent()).isEqualTo(10.0);
        assertThat(overview.taskRunning()).isEqualTo(2);
        assertThat(overview.tokenTotal()).isEqualTo(100L);
        assertThat(overview.sampleTime()).contains("2026-08-21");
    }

    @Test
    void overviewReturnsNullWhenNoSample() {
        when(sampleMapper.selectLatest()).thenReturn(null);

        assertThat(service.overview()).isNull();
    }

    @Test
    void timeseriesReturnsPointsForSupportedRanges() {
        when(sampleMapper.selectSince(org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(sample(LocalDateTime.of(2026, 8, 21, 11, 0))));

        assertThat(service.timeseries("1h")).hasSize(1);
        assertThat(service.timeseries("24h")).hasSize(1);
        assertThat(service.timeseries("7d")).hasSize(1);
        assertThat(service.timeseries("30d")).hasSize(1);
    }

    @Test
    void timeseriesRejectsUnsupportedRange() {
        assertThatThrownBy(() -> service.timeseries("99d"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void timeseriesEmptyWhenNoSamples() {
        when(sampleMapper.selectSince(org.mockito.ArgumentMatchers.any())).thenReturn(List.of());

        assertThat(service.timeseries("24h")).isEmpty();
    }
}
