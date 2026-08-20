package com.labex.monitor.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** 最近访问明细。 */
@Data
public class VisitorHit {
    private LocalDateTime requestTime;
    private String method;
    private String path;
    private Integer status;
    private Integer durationMs;
    private String ip;
    private Integer userId;
    /** 归属地展示文本（如 "中国 广东 深圳 电信"），无法解析时为 null。 */
    private String location;
}