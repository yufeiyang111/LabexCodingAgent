package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("t_ops_alert_notification")
public class OpsAlertNotification {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long alertId;
    private Long ruleId;
    private String channel;
    private String status;
    private Integer attempt;
    private String payload;
    private String errorMessage;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}