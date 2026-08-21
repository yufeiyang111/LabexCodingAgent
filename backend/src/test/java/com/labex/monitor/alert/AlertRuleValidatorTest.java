package com.labex.monitor.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.labex.monitor.dto.AlertRuleRequest;
import org.junit.jupiter.api.Test;

class AlertRuleValidatorTest {

    private final AlertRuleValidator validator = new AlertRuleValidator();

    private AlertRuleRequest valid() {
        AlertRuleRequest request = new AlertRuleRequest();
        request.setName("cpu high");
        request.setMetricKey("cpu_percent");
        request.setOperator("GT");
        request.setThreshold(90.0);
        request.setDurationMinutes(5);
        request.setCooldownMinutes(30);
        request.setSeverity("warning");
        request.setEnabled(true);
        return request;
    }

    @Test
    void validRulePasses() {
        validator.validate(valid());
    }

    @Test
    void nullRuleIsRejected() {
        assertThatThrownBy(() -> validator.validate(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("规则");
    }

    @Test
    void blankNameIsRejected() {
        AlertRuleRequest request = valid();
        request.setName(" ");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("名称");
    }

    @Test
    void unknownMetricKeyIsRejected() {
        AlertRuleRequest request = valid();
        request.setMetricKey("gpu_usage");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("指标键");
    }

    @Test
    void unknownOperatorIsRejected() {
        AlertRuleRequest request = valid();
        request.setOperator("LIKE");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("比较符");
    }

    @Test
    void nonFiniteThresholdIsRejected() {
        AlertRuleRequest request = valid();
        request.setThreshold(Double.NaN);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("阈值");
    }

    @Test
    void outOfRangeDurationIsRejected() {
        AlertRuleRequest request = valid();
        request.setDurationMinutes(0);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("评估窗口");
        request.setDurationMinutes(1441);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("评估窗口");
    }

    @Test
    void unknownSeverityIsRejected() {
        AlertRuleRequest request = valid();
        request.setSeverity("fatal");
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("严重度");
    }

    @Test
    void allMetricKeysAreAccepted() {
        for (String metricKey : AlertRuleValidator.METRIC_KEYS) {
            AlertRuleRequest request = valid();
            request.setMetricKey(metricKey);
            validator.validate(request);
        }
    }

    @Test
    void negativeCooldownIsRejected() {
        AlertRuleRequest request = valid();
        request.setCooldownMinutes(-1);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("冷却");
    }

    @Test
    void zeroDurationIsRejected() {
        AlertRuleRequest request = valid();
        request.setDurationMinutes(null);
        assertThatThrownBy(() -> validator.validate(request))
                .isInstanceOf(IllegalArgumentException.class);
    }
}