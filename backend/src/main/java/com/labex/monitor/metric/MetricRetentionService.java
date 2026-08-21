package com.labex.monitor.metric;

import com.labex.mapper.OpsMetricSampleMapper;
import java.time.LocalDateTime;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 运维指标采样保留策略：每日清理超过保留天数的明细（LIMIT 批量，防长锁）。 */
@Component
public class MetricRetentionService {

    private static final Logger log = LoggerFactory.getLogger(MetricRetentionService.class);

    private final OpsMetricSampleMapper sampleMapper;
    private final OpsMetricProperties properties;

    public MetricRetentionService(OpsMetricSampleMapper sampleMapper, OpsMetricProperties properties) {
        this.sampleMapper = sampleMapper;
        this.properties = properties;
    }

    @Scheduled(cron = "0 20 3 * * *")
    public void cleanupDaily() {
        cleanupExpired();
    }

    public void cleanupExpired() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(properties.getRetentionDays());
        int deleted = deleteInBatches(() -> sampleMapper.deleteBefore(cutoff));
        if (deleted > 0) {
            log.info("运维指标采样清理完成：{} 行", deleted);
        }
    }

    private int deleteInBatches(IntSupplier batch) {
        int total = 0;
        int deleted;
        do {
            deleted = batch.getAsInt();
            total += deleted;
        } while (deleted > 0);
        return total;
    }
}
