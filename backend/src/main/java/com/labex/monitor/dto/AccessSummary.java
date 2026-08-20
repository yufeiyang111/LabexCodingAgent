package com.labex.monitor.dto;

import lombok.Data;

/** 某时间窗口的访问汇总（今日/昨日共用）。 */
@Data
public class AccessSummary {
    private Long pv;
    private Long uv;
    private Long errorCount;
    private Double avgDurationMs;
}