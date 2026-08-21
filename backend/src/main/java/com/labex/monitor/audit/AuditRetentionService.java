package com.labex.monitor.audit;

import com.labex.mapper.OpsAuditLogMapper;
import com.labex.monitor.config.OpsAuditProperties;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 审计日志保留清理：按天批量删除过期记录，单次上限防长锁。 */
@Service
public class AuditRetentionService {

    private static final Logger log = LoggerFactory.getLogger(AuditRetentionService.class);

    private final OpsAuditLogMapper auditLogMapper;
    private final OpsAuditProperties properties;

    public AuditRetentionService(OpsAuditLogMapper auditLogMapper, OpsAuditProperties properties) {
        this.auditLogMapper = auditLogMapper;
        this.properties = properties;
    }

    @Scheduled(cron = "${labex-agent.monitor.audit.cleanup-cron:0 45 3 * * *}")
    public void cleanupScheduled() {
        cleanupBefore(LocalDateTime.now().minusDays(properties.getRetentionDays()));
    }

    public int cleanupBefore(LocalDateTime before) {
        int total = 0;
        int deleted;
        do {
            deleted = auditLogMapper.deleteBefore(before, properties.getCleanupBatchSize());
            total += deleted;
        } while (deleted > 0);
        log.info("Audit log retention cleanup removed {} records older than {}", total, before);
        return total;
    }
}