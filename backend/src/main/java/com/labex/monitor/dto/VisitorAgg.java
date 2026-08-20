package com.labex.monitor.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** 按 IP 聚合的访客来源统计。 */
@Data
public class VisitorAgg {
    private String ip;
    private Long pv;
    private LocalDateTime lastSeen;
    /** 归属地展示文本，无法解析时为 null。 */
    private String location;
}