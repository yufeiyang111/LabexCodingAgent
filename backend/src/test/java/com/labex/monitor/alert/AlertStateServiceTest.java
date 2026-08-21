package com.labex.monitor.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.labex.entity.OpsAlert;
import com.labex.mapper.OpsAlertMapper;
import com.labex.monitor.audit.AuditRecordingService;
import com.labex.monitor.event.OpsEventRecordingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlertStateServiceTest {

    private final OpsAlertMapper alertMapper = mock(OpsAlertMapper.class);
    private final AlertDeduplicationService deduplicationService = mock(AlertDeduplicationService.class);
    private final AuditRecordingService auditService = mock(AuditRecordingService.class);
    private final OpsEventRecordingService eventService = mock(OpsEventRecordingService.class);
    private AlertStateService service;

    @BeforeEach
    void setUp() {
        service = new AlertStateService(alertMapper, deduplicationService, auditService, eventService);
    }

    @Test
    void openCreatesFiringAlertWhenNoActiveExists() {
        when(deduplicationService.findActive(1L, "rule-1")).thenReturn(null);

        OpsAlert alert = service.open(1L, "rule-1", "warning", "cpu_percent", 95.0, 90.0, "cpu high");

        assertThat(alert.getStatus()).isEqualTo("FIRING");
        assertThat(alert.getFirstFiringAt()).isNotNull();
        assertThat(alert.getLastFiringAt()).isNotNull();
    }

    @Test
    void openRefreshesExistingActiveAlertInsteadOfDuplicating() {
        OpsAlert existing = new OpsAlert();
        existing.setAlertId(5L);
        existing.setStatus("FIRING");
        when(deduplicationService.findActive(1L, "rule-1")).thenReturn(existing);

        OpsAlert refreshed = service.open(1L, "rule-1", "critical", "cpu_percent", 99.0, 90.0, "cpu high");

        assertThat(refreshed).isSameAs(existing);
        assertThat(existing.getCurrentValue()).isEqualTo(99.0);
        assertThat(existing.getLastFiringAt()).isNotNull();
    }

    @Test
    void acknowledgeIsIdempotent() {
        OpsAlert acknowledged = new OpsAlert();
        acknowledged.setAlertId(5L);
        acknowledged.setStatus("ACKNOWLEDGED");
        when(alertMapper.selectById(5L)).thenReturn(acknowledged);

        assertThat(service.acknowledge(5L, "OPS_OPERATOR")).isTrue();
    }

    @Test
    void acknowledgeResolvedAlertFails() {
        OpsAlert resolved = new OpsAlert();
        resolved.setAlertId(5L);
        resolved.setStatus("RESOLVED");
        when(alertMapper.selectById(5L)).thenReturn(resolved);

        assertThat(service.acknowledge(5L, "OPS_OPERATOR")).isFalse();
    }

    @Test
    void acknowledgeMissingAlertFails() {
        when(alertMapper.selectById(99L)).thenReturn(null);
        assertThat(service.acknowledge(99L, "OPS_OPERATOR")).isFalse();
    }

    @Test
    void silenceRejectsInvalidDuration() {
        assertThatThrownBy(() -> service.silence(5L, 0, "OPS_OPERATOR"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("静默");
        assertThatThrownBy(() -> service.silence(5L, 10081, "OPS_OPERATOR"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void silenceSetsExpiry() {
        OpsAlert firing = new OpsAlert();
        firing.setAlertId(5L);
        firing.setStatus("FIRING");
        when(alertMapper.selectById(5L)).thenReturn(firing);

        assertThat(service.silence(5L, 60, "OPS_OPERATOR")).isTrue();
        assertThat(firing.getStatus()).isEqualTo("SILENCED");
        assertThat(firing.getSilencedUntil()).isNotNull();
    }

    @Test
    void resolveIsIdempotent() {
        OpsAlert resolved = new OpsAlert();
        resolved.setAlertId(5L);
        resolved.setStatus("RESOLVED");
        when(alertMapper.selectById(5L)).thenReturn(resolved);

        assertThat(service.resolve(5L, "OPS_OPERATOR", null)).isTrue();
    }

    @Test
    void resolveMissingAlertFails() {
        when(alertMapper.selectById(99L)).thenReturn(null);
        assertThat(service.resolve(99L, "OPS_OPERATOR", null)).isFalse();
    }

    @Test
    void reactivateOnlyAppliesToSilenced() {
        OpsAlert firing = new OpsAlert();
        firing.setAlertId(5L);
        firing.setStatus("FIRING");
        when(alertMapper.selectById(5L)).thenReturn(firing);

        assertThat(service.reactivate(5L)).isFalse();

        OpsAlert silenced = new OpsAlert();
        silenced.setAlertId(6L);
        silenced.setStatus("SILENCED");
        when(alertMapper.selectById(6L)).thenReturn(silenced);

        assertThat(service.reactivate(6L)).isTrue();
        assertThat(silenced.getStatus()).isEqualTo("FIRING");
    }
}