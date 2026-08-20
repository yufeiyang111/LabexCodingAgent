package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/** 站点访问小时聚合，保留 stats-retention-days 天。 */
@Data
@TableName(value = "t_access_stats")
public class AccessStats {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField(value = "stat_hour")
    private LocalDateTime statHour;
    @TableField(value = "path")
    private String path;
    @TableField(value = "pv")
    private Integer pv;
    @TableField(value = "uv")
    private Integer uv;
    @TableField(value = "error_count")
    private Integer errorCount;
    @TableField(value = "avg_duration_ms")
    private BigDecimal avgDurationMs;
}