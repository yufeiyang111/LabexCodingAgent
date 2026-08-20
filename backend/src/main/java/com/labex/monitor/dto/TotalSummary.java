package com.labex.monitor.dto;

import lombok.Data;

/** 全量累计访问量。 */
@Data
public class TotalSummary {
    private Long pv;
    private Long uv;
}