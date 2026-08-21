package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("t_ops_operation")
public class OpsOperation {
    @TableId(type = IdType.AUTO)
    private Long operationId;
    private String actionType;
    private String targetType;
    private String targetId;
    private String status;
    private String requestedBy;
    private String sourceIp;
    private String idempotencyKey;
    private String result;
    private String failureReason;
    private LocalDateTime requestedAt;
    private LocalDateTime completedAt;
    private LocalDateTime createTime;
}