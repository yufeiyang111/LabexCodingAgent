package com.labex.monitor.dto;

import lombok.Data;

/** 热门路径统计。 */
@Data
public class PathStat {
    private String path;
    private Long pv;
    private Long uv;
    private Long errorCount;
    private Double avgDurationMs;
}