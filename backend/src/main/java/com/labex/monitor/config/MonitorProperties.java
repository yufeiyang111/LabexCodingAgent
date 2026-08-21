package com.labex.monitor.config;

import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 站点监控模块可调参数集中配置。 */
@Data
@ConfigurationProperties(prefix = "labex-agent.monitor")
public class MonitorProperties {
    private boolean enabled = true;
    /** 访问监控页的校验码；为空表示未启用监控入口。 */
    private String accessCode = "";
    /** 可选的操作者校验码：用该码登录签发 OPS_OPERATOR 角色，否则只签发只读 OPS_VIEWER。 */
    private String operatorCode = "";
    private int sessionTtlHours = 24;
    private int authRateLimit = 5;
    private int authFailureWindowSeconds = 300;
    private int detailRetentionDays = 30;
    private int statsRetentionDays = 180;
    /** 停机/重启后补跑聚合最多往前补的小时数。 */
    private int aggCatchupMaxHours = 48;
    /** 不参与统计的 servlet 路径前缀。 */
    private List<String> excludedPrefixes = List.of("/ws/", "/preview/", "/error", "/ops/", "/auth/captcha");
}