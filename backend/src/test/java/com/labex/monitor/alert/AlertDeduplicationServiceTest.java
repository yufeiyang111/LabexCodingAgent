package com.labex.monitor.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.OpsAlert;
import com.labex.mapper.OpsAlertMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlertDeduplicationServiceTest {

    private final OpsAlertMapper alertMapper = mock(OpsAlertMapper.class);
    private AlertDeduplicationService service;

    @BeforeEach
    void setUp() {
        service = new AlertDeduplicationService(alertMapper);
    }

    @Test
    void fingerprintIsStablePerRule() {
        assertThat(service.fingerprintFor(7L)).isEqualTo("rule-7");
    }

    @Test
    void nullRuleIdReturnsNoActive() {
        assertThat(service.findActive(null, "rule-1")).isNull();
    }

    @Test
    void blankFingerprintReturnsNoActive() {
        assertThat(service.findActive(1L, "  ")).isNull();
    }

    @Test
    void activeAlertIsReturned() {
        OpsAlert active = new OpsAlert();
        when(alertMapper.selectActiveByRuleAndFingerprint(1L, "rule-1")).thenReturn(active);

        assertThat(service.findActive(1L, "rule-1")).isSameAs(active);
    }

    @Test
    void noAlertDefersCreationToCaller() {
        when(alertMapper.selectActiveByRuleAndFingerprint(1L, "rule-1")).thenReturn(null);

        assertThat(service.findActive(1L, "rule-1")).isNull();
        verify(alertMapper, never()).insert(org.mockito.ArgumentMatchers.any());
    }
}