package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("t_ops_alert_rule")
public class OpsAlertRule {
    @TableId(type = IdType.AUTO)
    private Long ruleId;
    private String name;
    private String metricKey;
    private String operator;
    private Double threshold;
    private Integer durationMinutes;
    private Integer cooldownMinutes;
    private String severity;
    private Integer enabled;
    private String description;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}