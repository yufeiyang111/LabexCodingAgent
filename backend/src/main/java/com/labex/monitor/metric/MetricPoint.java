package com.labex.monitor.metric;

/** 指标趋势序列点。 */
public record MetricPoint(
        String time,
        Double cpuPercent,
        Double memoryPercent,
        Double diskPercent,
        Double heapUsedBytes,
        Double heapMaxBytes,
        Double systemLoadAverage,
        Integer taskTotal,
        Integer taskRunning,
        Integer taskFailed,
        Long tokenTotal) {
}
