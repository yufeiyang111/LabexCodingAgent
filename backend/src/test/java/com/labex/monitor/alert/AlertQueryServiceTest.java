package com.labex.monitor.alert;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.labex.entity.OpsAlert;
import com.labex.mapper.OpsAlertMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class AlertQueryServiceTest {

    private final OpsAlertMapper alertMapper = mock(OpsAlertMapper.class);
    private final AlertRuleService ruleService = mock(AlertRuleService.class);
    private final AlertQueryService service = new AlertQueryService(alertMapper, ruleService);

    @Test
    void queryWithNullStatusAndSeverityDoesNotThrow() {
        when(alertMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<OpsAlert>());

        assertThat(service.query(1, 20, null, null)).isEmpty();
    }

    @Test
    void queryWithBlankStatusDoesNotThrow() {
        when(alertMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<OpsAlert>());

        assertThat(service.query(1, 20, "  ", null)).isEmpty();
    }

    @Test
    void queryNormalizesStatusToUpperCase() {
        when(alertMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<OpsAlert>());

        assertThat(service.query(1, 20, "firing", null)).isEmpty();

        verify(alertMapper).selectPage(any(Page.class), any(Wrapper.class));
    }

    @Test
    void queryRejectsUnsupportedStatus() {
        assertThatThrownBy(() -> service.query(1, 20, "BOGUS", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void queryReturnsMappedRows() {
        OpsAlert alert = new OpsAlert();
        alert.setAlertId(1L);
        alert.setRuleId(10L);
        alert.setStatus("FIRING");
        alert.setSeverity("warning");
        alert.setMetricKey("cpu.percent");
        Page<OpsAlert> page = new Page<>();
        page.setRecords(List.of(alert));
        when(alertMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        assertThat(service.query(1, 20, null, null)).hasSize(1);
    }
}