package com.labex.monitor.incident;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.labex.entity.OpsIncident;
import com.labex.mapper.OpsIncidentMapper;
import com.labex.monitor.audit.AuditRecordingService;
import com.labex.monitor.event.OpsEventQueryService;
import com.labex.monitor.event.OpsEventRecordingService;
import java.util.List;
import org.junit.jupiter.api.Test;

class IncidentServiceTest {

    private final OpsIncidentMapper incidentMapper = mock(OpsIncidentMapper.class);
    private final IncidentStateMachine stateMachine = mock(IncidentStateMachine.class);
    private final AuditRecordingService auditService = mock(AuditRecordingService.class);
    private final OpsEventRecordingService eventService = mock(OpsEventRecordingService.class);
    private final OpsEventQueryService eventQueryService = mock(OpsEventQueryService.class);
    private final IncidentService service = new IncidentService(incidentMapper, stateMachine,
            auditService, eventService, eventQueryService);

    @Test
    void queryWithNullStatusAndSeverityDoesNotThrow() {
        when(incidentMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<OpsIncident>());

        assertThat(service.query(1, 20, null, null)).isEmpty();
    }

    @Test
    void queryWithBlankStatusDoesNotThrow() {
        when(incidentMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(new Page<OpsIncident>());

        assertThat(service.query(1, 20, " ", null)).isEmpty();
    }

    @Test
    void countWithNullStatusDoesNotThrow() {
        when(incidentMapper.selectCount(any(Wrapper.class))).thenReturn(0L);

        assertThat(service.count(null, null)).isZero();
    }

    @Test
    void queryReturnsMappedRows() {
        OpsIncident incident = new OpsIncident();
        incident.setIncidentId(1L);
        incident.setTitle("磁盘使用率过高");
        incident.setStatus("OPEN");
        incident.setSeverity("warning");
        Page<OpsIncident> page = new Page<>();
        page.setRecords(List.of(incident));
        when(incidentMapper.selectPage(any(Page.class), any(Wrapper.class))).thenReturn(page);

        assertThat(service.query(1, 20, null, null)).hasSize(1);
    }
}