package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("t_ops_incident")
public class OpsIncident {
    @TableId(type = IdType.AUTO)
    private Long incidentId;
    private String title;
    private String severity;
    private String status;
    private Long sourceAlertId;
    private String summary;
    private LocalDateTime openedAt;
    private LocalDateTime acknowledgedAt;
    private LocalDateTime resolvedAt;
    private LocalDateTime closedAt;
    private String acknowledgedBy;
    private String resolvedBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}