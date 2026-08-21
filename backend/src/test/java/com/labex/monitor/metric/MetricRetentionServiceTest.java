package com.labex.monitor.metric;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.mapper.OpsMetricSampleMapper;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class MetricRetentionServiceTest {

    private final OpsMetricSampleMapper sampleMapper = mock(OpsMetricSampleMapper.class);
    private final OpsMetricProperties properties = new OpsMetricProperties();
    private final MetricRetentionService service = new MetricRetentionService(sampleMapper, properties);

    @Test
    void cleanupDeletesInBatchesUntilZero() {
        when(sampleMapper.deleteBefore(any())).thenReturn(5000, 5000, 3, 0);

        service.cleanupExpired();

        verify(sampleMapper, times(4)).deleteBefore(any());
    }

    @Test
    void cleanupWithNothingToDelete() {
        when(sampleMapper.deleteBefore(any())).thenReturn(0);

        service.cleanupExpired();

        verify(sampleMapper).deleteBefore(any());
    }

    @Test
    void retentionDaysIsAppliedAsCutoff() {
        properties.setRetentionDays(10);
        when(sampleMapper.deleteBefore(any())).thenReturn(0);

        service.cleanupExpired();

        org.mockito.ArgumentCaptor<LocalDateTime> captor =
                org.mockito.ArgumentCaptor.forClass(LocalDateTime.class);
        verify(sampleMapper).deleteBefore(captor.capture());
        LocalDateTime cutoff = captor.getValue();
        assertThat(cutoff).isBefore(LocalDateTime.now().minusDays(9));
        assertThat(cutoff).isAfter(LocalDateTime.now().minusDays(11));
    }
}
