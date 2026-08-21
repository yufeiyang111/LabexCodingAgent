package com.labex.monitor.alert;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.labex.entity.OpsAlertRule;
import com.labex.monitor.dto.AlertRuleRequest;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlertRuleSeederTest {

    private final AlertRuleService ruleService = mock(AlertRuleService.class);
    private final AlertRuleSeeder seeder = new AlertRuleSeeder(ruleService);

    @Test
    void seedsDefaultsOnlyWhenTableEmpty() {
        when(ruleService.list(false)).thenReturn(List.of());

        seeder.run(null);

        verify(ruleService, times(3)).create(any(AlertRuleRequest.class));
    }

    @Test
    void doesNotTouchExistingUserRules() {
        when(ruleService.list(false)).thenReturn(List.of(new OpsAlertRule()));

        seeder.run(null);

        verify(ruleService, never()).create(any(AlertRuleRequest.class));
    }

    @Test
    void continuesAfterSingleInvalidDefault() {
        when(ruleService.list(false)).thenReturn(List.of());
        doThrow(new IllegalArgumentException("bad rule")).doReturn(new OpsAlertRule())
                .doReturn(new OpsAlertRule()).when(ruleService).create(any(AlertRuleRequest.class));

        seeder.run(null);

        verify(ruleService, times(3)).create(any(AlertRuleRequest.class));
    }

    @Test
    void defaultsAreWellFormed() {
        for (AlertRuleRequest request : AlertRuleSeeder.defaults()) {
            new AlertRuleValidator().validate(request);
        }
    }
}