package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("t_ops_alert")
public class OpsAlert {
    @TableId(type = IdType.AUTO)
    private Long alertId;
    private Long ruleId;
    private String fingerprint;
    private String status;
    private String severity;
    private String metricKey;
    private Double currentValue;
    private Double threshold;
    private String message;
    private LocalDateTime firstFiringAt;
    private LocalDateTime lastFiringAt;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime silencedAt;
    private LocalDateTime silencedUntil;
    private String acknowledgedBy;
    private String resolvedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}