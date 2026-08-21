package com.labex.monitor.metric;

/** 最新一次指标采样概览。 */
public record MetricOverview(
        String sampleTime,
        Double cpuPercent,
        Double memoryPercent,
        Double diskPercent,
        Double heapUsedBytes,
        Double heapMaxBytes,
        Double systemLoadAverage,
        Integer taskTotal,
        Integer taskRunning,
        Integer taskWaiting,
        Integer taskCompleted,
        Integer taskFailed,
        Integer taskCancelled,
        Long tokenTotal,
        Long tokenPromptTotal,
        Long tokenCompletionTotal) {
}
