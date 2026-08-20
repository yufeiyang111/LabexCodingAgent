package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 站点访问明细，仅保留 detail-retention-days 天。 */
@Data
@TableName(value = "t_access_log")
public class AccessLog {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField(value = "request_time")
    private LocalDateTime requestTime;
    @TableField(value = "path")
    private String path;
    @TableField(value = "method")
    private String method;
    @TableField(value = "status")
    private Integer status;
    @TableField(value = "duration_ms")
    private Integer durationMs;
    @TableField(value = "ip")
    private String ip;
    @TableField(value = "user_id")
    private Integer userId;
    @TableField(value = "user_agent")
    private String userAgent;
}