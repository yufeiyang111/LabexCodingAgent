package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

@Data
@TableName("t_ops_event")
public class OpsEvent {
    @TableId(type = IdType.AUTO)
    private Long eventId;
    private String eventType;
    private String severity;
    private String source;
    private String targetType;
    private String targetId;
    private String message;
    private String detail;
    private String operator;
    private LocalDateTime createTime;
}