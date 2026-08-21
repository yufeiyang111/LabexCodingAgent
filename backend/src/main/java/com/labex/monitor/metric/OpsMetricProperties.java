package com.labex.monitor.metric;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 运维指标低频采样与保留策略可调参数集中配置。 */
@Data
@ConfigurationProperties(prefix = "labex-agent.monitor.metric")
public class OpsMetricProperties {
    /** 系统/Agent/token 指标采样间隔（毫秒），低频采样避免频繁写库。 */
    private long sampleIntervalMs = 300000;
    /** 指标采样明细保留天数，超过后由每日清理任务删除。 */
    private int retentionDays = 30;
}
