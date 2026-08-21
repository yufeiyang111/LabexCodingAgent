package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("t_ops_audit_log")
public class OpsAuditLog {
    @TableId(type = IdType.AUTO)
    private Long auditId;
    private String operatorId;
    private String operatorRole;
    private String sourceIp;
    private String actionType;
    private String targetType;
    private String targetId;
    private String reason;
    private String beforeState;
    private String afterState;
    private String result;
    private String failureReason;
    private LocalDateTime createTime;
}