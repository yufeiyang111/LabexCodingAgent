package com.labex.monitor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 审计与事件模块可调参数集中配置。 */
@Data
@ConfigurationProperties(prefix = "labex-agent.monitor.audit")
public class OpsAuditProperties {
    /** 审计日志保留天数。 */
    private int retentionDays = 180;
    /** 清理任务 cron（默认每天 03:45）。 */
    private String cleanupCron = "0 45 3 * * *";
    /** 单次清理批次行数。 */
    private int cleanupBatchSize = 5000;
}