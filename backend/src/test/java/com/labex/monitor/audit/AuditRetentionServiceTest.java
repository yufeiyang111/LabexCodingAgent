package com.labex.monitor.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.mapper.OpsAuditLogMapper;
import com.labex.monitor.config.OpsAuditProperties;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AuditRetentionServiceTest {

    private final OpsAuditLogMapper auditLogMapper = mock(OpsAuditLogMapper.class);
    private final OpsAuditProperties properties = new OpsAuditProperties();
    private AuditRetentionService service;

    @BeforeEach
    void setUp() {
        service = new AuditRetentionService(auditLogMapper, properties);
    }

    @Test
    void cleanupDeletesInBatchesUntilZero() {
        when(auditLogMapper.deleteBefore(any(LocalDateTime.class), eq(5000)))
                .thenReturn(5000, 5000, 200, 0);

        int total = service.cleanupBefore(LocalDateTime.now().minusDays(180));

        assertThat(total).isEqualTo(10200);
        verify(auditLogMapper, times(4)).deleteBefore(any(LocalDateTime.class), eq(5000));
    }

    @Test
    void cleanupWithNothingToDelete() {
        when(auditLogMapper.deleteBefore(any(LocalDateTime.class), eq(5000)))
                .thenReturn(0);

        int total = service.cleanupBefore(LocalDateTime.now().minusDays(180));

        assertThat(total).isZero();
        verify(auditLogMapper, times(1)).deleteBefore(any(LocalDateTime.class), eq(5000));
    }

    @Test
    void cleanupScheduledInvokesCleanupBeforeWithCutoff() {
        properties.setRetentionDays(90);
        properties.setCleanupBatchSize(1000);
        when(auditLogMapper.deleteBefore(any(LocalDateTime.class), eq(1000))).thenReturn(0);

        service.cleanupScheduled();

        verify(auditLogMapper).deleteBefore(any(LocalDateTime.class), eq(1000));
    }
}
