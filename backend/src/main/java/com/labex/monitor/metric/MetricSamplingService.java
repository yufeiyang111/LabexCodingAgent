package com.labex.monitor.metric;

import com.labex.entity.OpsMetricSample;
import com.labex.mapper.AgentTaskMapper;
import com.labex.mapper.AgentTokenUsageMapper;
import com.labex.mapper.OpsMetricSampleMapper;
import com.labex.monitor.system.SystemMetricsService;
import com.labex.monitor.system.SystemSnapshot;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 运维指标低频采样：定时把系统（CPU/内存/磁盘/JVM）、Agent 任务状态分布与 token 用量
 * 落库到 t_ops_metric_sample。单个数据源异常被隔离，不影响整次采样；按采样分钟幂等。
 */
@Component
public class MetricSamplingService {

    private static final Logger log = LoggerFactory.getLogger(MetricSamplingService.class);

    private final SystemMetricsService systemMetricsService;
    private final AgentTaskMapper taskMapper;
    private final AgentTokenUsageMapper tokenUsageMapper;
    private final OpsMetricSampleMapper sampleMapper;
    private final OpsMetricProperties properties;

    public MetricSamplingService(SystemMetricsService systemMetricsService,
                                 AgentTaskMapper taskMapper,
                                 AgentTokenUsageMapper tokenUsageMapper,
                                 OpsMetricSampleMapper sampleMapper,
                                 OpsMetricProperties properties) {
        this.systemMetricsService = systemMetricsService;
        this.taskMapper = taskMapper;
        this.tokenUsageMapper = tokenUsageMapper;
        this.sampleMapper = sampleMapper;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${labex-agent.monitor.metric.sample-interval-ms:300000}")
    public void sampleScheduled() {
        try {
            sample();
        } catch (Exception e) {
            log.warn("metric sampling failed: {}", e.getClass().getSimpleName());
        }
    }

    public void sample() {
        LocalDateTime sampleTime = LocalDateTime.now().withNano(0).withSecond(0);
        OpsMetricSample sample = new OpsMetricSample();
        sample.setSampleTime(sampleTime);
        collectSystem(sample);
        collectTasks(sample);
        collectTokens(sample);
        try {
            sampleMapper.insert(sample);
        } catch (DuplicateKeyException duplicate) {
            // 同一采样分钟已存在（调度抖动重跑），幂等忽略。
        }
    }

    private void collectSystem(OpsMetricSample sample) {
        try {
            SystemSnapshot snapshot = systemMetricsService.snapshot();
            sample.setCpuPercent(snapshot.getCpuPercent());
            sample.setMemoryPercent(snapshot.getMemoryPercent());
            sample.setMemoryUsedBytes(snapshot.getMemoryUsedBytes());
            sample.setDiskPercent(snapshot.getDiskPercent());
            sample.setDiskUsedBytes(snapshot.getDiskUsedBytes());
            sample.setHeapUsedBytes(snapshot.getHeapUsedBytes());
            sample.setHeapMaxBytes(snapshot.getHeapMaxBytes());
            sample.setSystemLoadAverage(snapshot.getSystemLoadAverage() < 0 ? null : snapshot.getSystemLoadAverage());
        } catch (Exception e) {
            log.warn("metric system collection failed: {}", e.getClass().getSimpleName());
        }
    }

    private void collectTasks(OpsMetricSample sample) {
        try {
            List<Map<String, Object>> counts = taskMapper.selectStatusCounts();
            int total = 0;
            int running = 0;
            int waiting = 0;
            int completed = 0;
            int failed = 0;
            int cancelled = 0;
            for (Map<String, Object> row : counts) {
                String status = stringValue(row.get("status_value"));
                int count = intValue(row.get("count_value"));
                total += count;
                switch (status) {
                    case "running" -> running += count;
                    case "completed" -> completed += count;
                    case "failed" -> failed += count;
                    case "cancelled" -> cancelled += count;
                    default -> waiting += count;
                }
            }
            sample.setTaskTotal(total);
            sample.setTaskRunning(running);
            sample.setTaskWaiting(waiting);
            sample.setTaskCompleted(completed);
            sample.setTaskFailed(failed);
            sample.setTaskCancelled(cancelled);
        } catch (Exception e) {
            log.warn("metric task collection failed: {}", e.getClass().getSimpleName());
        }
    }

    private void collectTokens(OpsMetricSample sample) {
        try {
            LocalDateTime since = LocalDateTime.now().minus(Duration.ofMillis(properties.getSampleIntervalMs()));
            Map<String, Object> totals = tokenUsageMapper.selectWindowTotals(since);
            sample.setTokenPromptTotal(longValue(totals.get("prompt_tokens")));
            sample.setTokenCompletionTotal(longValue(totals.get("completion_tokens")));
            sample.setTokenTotal(longValue(totals.get("total_tokens")));
        } catch (Exception e) {
            log.warn("metric token collection failed: {}", e.getClass().getSimpleName());
        }
    }

    private static String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static int intValue(Object value) {
        if (value == null) {
            return 0;
        }
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private static long longValue(Object value) {
        if (value == null) {
            return 0L;
        }
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }
}
