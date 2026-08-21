package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 运维指标低频采样明细（每分钟/每数分钟一行，宽表，用于历史趋势与数据清理）。 */
@Data
@TableName("t_ops_metric_sample")
public class OpsMetricSample {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("sample_time")
    private LocalDateTime sampleTime;

    @TableField("cpu_percent")
    private Double cpuPercent;

    @TableField("memory_percent")
    private Double memoryPercent;

    @TableField("memory_used_bytes")
    private Long memoryUsedBytes;

    @TableField("disk_percent")
    private Double diskPercent;

    @TableField("disk_used_bytes")
    private Long diskUsedBytes;

    @TableField("heap_used_bytes")
    private Long heapUsedBytes;

    @TableField("heap_max_bytes")
    private Long heapMaxBytes;

    @TableField("system_load_average")
    private Double systemLoadAverage;

    @TableField("task_total")
    private Integer taskTotal;

    @TableField("task_running")
    private Integer taskRunning;

    @TableField("task_waiting")
    private Integer taskWaiting;

    @TableField("task_completed")
    private Integer taskCompleted;

    @TableField("task_failed")
    private Integer taskFailed;

    @TableField("task_cancelled")
    private Integer taskCancelled;

    @TableField("token_prompt_total")
    private Long tokenPromptTotal;

    @TableField("token_completion_total")
    private Long tokenCompletionTotal;

    @TableField("token_total")
    private Long tokenTotal;

    @TableField("create_time")
    private LocalDateTime createTime;
}
