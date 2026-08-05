package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/** 记录旧版 reader 的迁移命中和删除门槛；不参与 Agent 运行时状态。 */
@Data
@TableName("t_agent_legacy_migration_gate")
public class AgentLegacyMigrationGate {
    @TableId(value = "reader_key", type = IdType.INPUT)
    private String readerKey;

    @TableField("target_removal_version")
    private String targetRemovalVersion;

    @TableField("observation_window_days")
    private Integer observationWindowDays;

    @TableField("read_hit_count")
    private Long readHitCount;

    @TableField("source_item_hit_count")
    private Long sourceItemHitCount;

    @TableField("last_read_hit_at")
    private LocalDateTime lastReadHitAt;

    @TableField("pending_source_count")
    private Long pendingSourceCount;

    @TableField("last_inventory_at")
    private LocalDateTime lastInventoryAt;

    @TableField("zero_inventory_since")
    private LocalDateTime zeroInventorySince;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
