package com.labex.monitor.controller;

import com.labex.common.Result;
import com.labex.monitor.metric.MetricOverview;
import com.labex.monitor.metric.MetricPoint;
import com.labex.monitor.metric.MetricQueryService;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 运维指标查询接口（/ops/metrics/**，受 MonitorAuthInterceptor 保护）。 */
@RestController
@RequestMapping("/ops/metrics")
public class MonitorMetricController {

    private static final Set<String> VALID_RANGES = Set.of("1h", "24h", "7d", "30d");

    private final MetricQueryService queryService;

    public MonitorMetricController(MetricQueryService queryService) {
        this.queryService = queryService;
    }

    @GetMapping("/overview")
    public Result<MetricOverview> overview() {
        MetricOverview overview = queryService.overview();
        if (overview == null) {
            return Result.error(404, "暂无指标数据，等待首次采样");
        }
        return Result.success(overview);
    }

    @GetMapping("/timeseries")
    public ResponseEntity<Result<List<MetricPoint>>> timeseries(
            @RequestParam(defaultValue = "24h") String range) {
        if (!VALID_RANGES.contains(range)) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(Result.error(-1, "不支持的统计区间: " + range));
        }
        return ResponseEntity.ok(Result.success(queryService.timeseries(range)));
    }
}
