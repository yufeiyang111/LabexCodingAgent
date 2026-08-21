package com.labex.monitor.runtime;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** 运维 Agent 运行态查询模块可调参数集中配置。 */
@Data
@ConfigurationProperties(prefix = "labex-agent.monitor.runtime")
public class MonitorRuntimeProperties {
    /** 任务列表默认每页条数。 */
    private int defaultPageSize = 20;
    /** 任务列表每页条数上限。 */
    private int maxPageSize = 200;
    /** 事件时间线默认返回条数。 */
    private int eventTimelineLimit = 50;
    /** 事件时间线单次返回条数上限。 */
    private int eventTimelineMaxLimit = 500;
    /** 事件 payload 面向运维展示的最大字符数（超过截断，避免泄露敏感内容）。 */
    private int eventPayloadMaxChars = 2000;
    /** 任务摘要面向运维展示的最大字符数。 */
    private int summaryMaxChars = 200;
}
