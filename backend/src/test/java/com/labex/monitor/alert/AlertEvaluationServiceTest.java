package com.labex.monitor.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.OpsAlert;
import com.labex.entity.OpsAlertRule;
import com.labex.entity.OpsMetricSample;
import com.labex.mapper.OpsMetricSampleMapper;
import com.labex.monitor.config.OpsAlertProperties;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AlertEvaluationServiceTest {

    private final AlertRuleService ruleService = mock(AlertRuleService.class);
    private final AlertDeduplicationService deduplicationService = mock(AlertDeduplicationService.class);
    private final AlertStateService stateService = mock(AlertStateService.class);
    private final AlertNotificationService notificationService = mock(AlertNotificationService.class);
    private final OpsMetricSampleMapper sampleMapper = mock(OpsMetricSampleMapper.class);
    private final OpsAlertProperties properties = new OpsAlertProperties();
    private AlertEvaluationService service;

    private OpsAlertRule rule;

    @BeforeEach
    void setUp() {
        service = new AlertEvaluationService(ruleService, deduplicationService, stateService,
                notificationService, sampleMapper, properties);
        rule = new OpsAlertRule();
        rule.setRuleId(1L);
        rule.setName("cpu high");
        rule.setMetricKey("cpu_percent");
        rule.setOperator("GT");
        rule.setThreshold(90.0);
        rule.setDurationMinutes(5);
        rule.setCooldownMinutes(30);
        rule.setSeverity("warning");
        rule.setEnabled(1);
        when(deduplicationService.fingerprintFor(1L)).thenReturn("rule-1");
    }

    private OpsMetricSample sample(double cpu) {
        OpsMetricSample sample = new OpsMetricSample();
        sample.setSampleTime(LocalDateTime.now());
        sample.setCpuPercent(cpu);
        return sample;
    }

    @Test
    void noRulesEvaluatesZero() {
        when(ruleService.list(true)).thenReturn(List.of());
        assertThat(service.evaluateOnce()).isZero();
    }

    @Test
    void ruleBreachOpensAlertAndNotifies() {
        when(ruleService.list(true)).thenReturn(List.of(rule));
        when(sampleMapper.selectSince(any())).thenReturn(List.of(sample(95.0)));
        when(deduplicationService.findActive(1L, "rule-1")).thenReturn(null);
        OpsAlert fired = new OpsAlert();
        fired.setAlertId(9L);
        when(stateService.open(anyLong(), anyString(), anyString(), anyString(), anyDouble(),
                anyDouble(), anyString())).thenReturn(fired);

        assertThat(service.evaluateOnce()).isEqualTo(1);
        verify(stateService).open(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("rule-1"),
                org.mockito.ArgumentMatchers.eq("warning"),
                org.mockito.ArgumentMatchers.eq("cpu_percent"),
                org.mockito.ArgumentMatchers.eq(95.0),
                org.mockito.ArgumentMatchers.eq(90.0), anyString());
        verify(notificationService).notifyFiring(fired);
    }

    @Test
    void ruleWithinThresholdDoesNotOpen() {
        when(ruleService.list(true)).thenReturn(List.of(rule));
        when(sampleMapper.selectSince(any())).thenReturn(List.of(sample(50.0)));

        service.evaluateOnce();

        verify(stateService, never()).open(anyLong(), anyString(), anyString(), anyString(),
                anyDouble(), anyDouble(), anyString());
    }

    @Test
    void noSamplesInWindowDoesNotOpen() {
        when(ruleService.list(true)).thenReturn(List.of(rule));
        when(sampleMapper.selectSince(any())).thenReturn(List.of());

        service.evaluateOnce();

        verify(stateService, never()).open(anyLong(), anyString(), anyString(), anyString(),
                anyDouble(), anyDouble(), anyString());
    }

    @Test
    void recoveredConditionResolvesActiveAlert() {
        when(ruleService.list(true)).thenReturn(List.of(rule));
        when(sampleMapper.selectSince(any())).thenReturn(List.of(sample(50.0)));
        OpsAlert active = new OpsAlert();
        active.setAlertId(9L);
        active.setStatus("FIRING");
        when(deduplicationService.findActive(1L, "rule-1")).thenReturn(active);

        service.evaluateOnce();

        verify(stateService).resolve(9L, "system", "condition cleared");
    }

    @Test
    void expiredSilenceIsReactivatedAndNotified() {
        when(ruleService.list(true)).thenReturn(List.of(rule));
        when(sampleMapper.selectSince(any())).thenReturn(List.of(sample(95.0)));
        OpsAlert silenced = new OpsAlert();
        silenced.setAlertId(9L);
        silenced.setStatus("SILENCED");
        silenced.setSilencedUntil(LocalDateTime.now().minusMinutes(1));
        when(deduplicationService.findActive(1L, "rule-1")).thenReturn(silenced);
        when(stateService.reactivate(9L)).thenReturn(true);

        service.evaluateOnce();

        verify(stateService).reactivate(9L);
        verify(notificationService).notifyFiring(silenced);
    }

    @Test
    void activeSilenceNotExpiredStaysSilent() {
        when(ruleService.list(true)).thenReturn(List.of(rule));
        when(sampleMapper.selectSince(any())).thenReturn(List.of(sample(95.0)));
        OpsAlert silenced = new OpsAlert();
        silenced.setAlertId(9L);
        silenced.setStatus("SILENCED");
        silenced.setSilencedUntil(LocalDateTime.now().plusMinutes(30));
        when(deduplicationService.findActive(1L, "rule-1")).thenReturn(silenced);

        service.evaluateOnce();

        verify(stateService, never()).reactivate(anyLong());
        verify(notificationService, never()).notifyFiring(any());
    }

    @Test
    void mapperFailureIsIsolatedPerRule() {
        OpsAlertRule broken = new OpsAlertRule();
        broken.setRuleId(2L);
        broken.setName("broken");
        broken.setMetricKey("cpu_percent");
        broken.setOperator("GT");
        broken.setThreshold(90.0);
        broken.setDurationMinutes(5);
        broken.setSeverity("warning");
        broken.setEnabled(1);
        when(ruleService.list(true)).thenReturn(List.of(broken, rule));
        when(sampleMapper.selectSince(any())).thenThrow(new RuntimeException("db down"))
                .thenReturn(List.of(sample(95.0)));
        when(deduplicationService.findActive(1L, "rule-1")).thenReturn(null);
        when(stateService.open(anyLong(), anyString(), anyString(), anyString(), anyDouble(),
                anyDouble(), anyString())).thenReturn(new OpsAlert());

        assertThat(service.evaluateOnce()).isEqualTo(1);
    }

    @Test
    void heapRatioUsesMaxHeapGuard() {
        rule.setMetricKey("heap_used_ratio");
        rule.setThreshold(40.0);
        when(ruleService.list(true)).thenReturn(List.of(rule));
        OpsMetricSample sample = new OpsMetricSample();
        sample.setHeapUsedBytes(512L * 1024 * 1024);
        sample.setHeapMaxBytes(1024L * 1024 * 1024);
        when(sampleMapper.selectSince(any())).thenReturn(List.of(sample));
        when(deduplicationService.findActive(1L, "rule-1")).thenReturn(null);
        when(stateService.open(anyLong(), anyString(), anyString(), anyString(), anyDouble(),
                anyDouble(), anyString())).thenReturn(new OpsAlert());

        service.evaluateOnce();

        verify(stateService).open(org.mockito.ArgumentMatchers.eq(1L),
                org.mockito.ArgumentMatchers.eq("rule-1"),
                org.mockito.ArgumentMatchers.eq("warning"),
                org.mockito.ArgumentMatchers.eq("heap_used_ratio"),
                org.mockito.ArgumentMatchers.eq(50.0),
                org.mockito.ArgumentMatchers.eq(40.0), anyString());
    }
}