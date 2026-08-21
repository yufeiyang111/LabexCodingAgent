package com.labex.monitor.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 告警模块可调参数集中配置。 */
@Data
@ConfigurationProperties(prefix = "labex-agent.monitor.alert")
public class OpsAlertProperties {
    /** 告警规则评估周期（毫秒）。 */
    private long evaluationIntervalMs = 60_000;
    /** 通知最大尝试次数（含首次发送）。 */
    private int notificationMaxAttempts = 3;
    /** 通知失败后的重试延迟（分钟）。 */
    private int notificationRetryDelayMinutes = 5;
    /** 可选通知 Webhook 地址；为空时通知只落库标记为 skipped。 */
    private String webhookUrl = "";
    /** 手动重试失败任务时的模型最大尝试次数。 */
    private int taskRetryMaxAttempts = 1;
    /** 单次评估最多处理的活跃告警数。 */
    private int evaluationBatchSize = 200;
}