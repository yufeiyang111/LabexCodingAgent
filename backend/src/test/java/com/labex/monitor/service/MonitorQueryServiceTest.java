package com.labex.monitor.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.labex.mapper.AccessLogMapper;
import com.labex.monitor.geo.IpLocationService;
import org.junit.jupiter.api.Test;

class MonitorQueryServiceTest {

    private final AccessLogMapper mapper = mock(AccessLogMapper.class);
    private final MonitorQueryService service = new MonitorQueryService(mapper, mock(IpLocationService.class));

    @Test
    void trafficMapsRangeToQuery() {
        service.traffic("24h");
        verify(mapper).trafficByHour(any());

        service.traffic("7d");
        verify(mapper).trafficByDay(7);

        service.traffic("30d");
        verify(mapper).trafficByDay(30);
    }

    @Test
    void trafficRejectsUnsupportedRange() {
        assertThatThrownBy(() -> service.traffic("1y"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void clampsQueryParameters() {
        service.topPaths(0, 1000);
        verify(mapper).topPaths(1, 100);

        service.recentVisitors(1000);
        verify(mapper).recentVisitors(200);
    }
}