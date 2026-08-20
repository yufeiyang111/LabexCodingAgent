package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 访问聚合幂等标记：记录已聚合的小时，保证补跑/重启不重复累加。 */
@Data
@TableName(value = "t_access_agg_marker")
public class AccessAggMarker {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField(value = "hour_start")
    private LocalDateTime hourStart;
    @TableField(value = "create_time")
    private LocalDateTime createTime;
}