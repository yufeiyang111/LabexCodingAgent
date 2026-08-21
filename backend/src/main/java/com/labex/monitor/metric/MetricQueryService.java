package com.labex.monitor.metric;

import com.labex.entity.OpsMetricSample;
import com.labex.mapper.OpsMetricSampleMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/** 运维指标只读查询用例：最新概览与时间范围趋势序列。 */
@Service
public class MetricQueryService {

    private final OpsMetricSampleMapper sampleMapper;

    public MetricQueryService(OpsMetricSampleMapper sampleMapper) {
        this.sampleMapper = sampleMapper;
    }

    public MetricOverview overview() {
        OpsMetricSample latest = sampleMapper.selectLatest();
        return latest == null ? null : toOverview(latest);
    }

    public List<MetricPoint> timeseries(String range) {
        Duration duration = switch (range) {
            case "1h" -> Duration.ofHours(1);
            case "24h" -> Duration.ofHours(24);
            case "7d" -> Duration.ofDays(7);
            case "30d" -> Duration.ofDays(30);
            default -> throw new IllegalArgumentException("Unsupported range: " + range);
        };
        LocalDateTime since = LocalDateTime.now().minus(duration);
        return sampleMapper.selectSince(since).stream()
                .map(this::toPoint)
                .collect(Collectors.toList());
    }

    private MetricOverview toOverview(OpsMetricSample sample) {
        return new MetricOverview(
                sample.getSampleTime() == null ? null : sample.getSampleTime().toString(),
                sample.getCpuPercent(),
                sample.getMemoryPercent(),
                sample.getDiskPercent(),
                doubleValue(sample.getHeapUsedBytes()),
                doubleValue(sample.getHeapMaxBytes()),
                sample.getSystemLoadAverage(),
                sample.getTaskTotal(),
                sample.getTaskRunning(),
                sample.getTaskWaiting(),
                sample.getTaskCompleted(),
                sample.getTaskFailed(),
                sample.getTaskCancelled(),
                sample.getTokenTotal(),
                sample.getTokenPromptTotal(),
                sample.getTokenCompletionTotal());
    }

    private MetricPoint toPoint(OpsMetricSample sample) {
        return new MetricPoint(
                sample.getSampleTime() == null ? null : sample.getSampleTime().toString(),
                sample.getCpuPercent(),
                sample.getMemoryPercent(),
                sample.getDiskPercent(),
                doubleValue(sample.getHeapUsedBytes()),
                doubleValue(sample.getHeapMaxBytes()),
                sample.getSystemLoadAverage(),
                sample.getTaskTotal(),
                sample.getTaskRunning(),
                sample.getTaskFailed(),
                sample.getTokenTotal());
    }

    private static Double doubleValue(Long value) {
        return value == null ? null : value.doubleValue();
    }
}
