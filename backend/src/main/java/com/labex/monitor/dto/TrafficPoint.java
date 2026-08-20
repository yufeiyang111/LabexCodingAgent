package com.labex.monitor.dto;

import lombok.Data;

/** 时间序列流量点（time 为 'yyyy-MM-dd HH:00:00' 或 'yyyy-MM-dd'）。 */
@Data
public class TrafficPoint {
    private String time;
    private Long pv;
    private Long uv;
    private Long errorCount;
    private Double avgDurationMs;
}