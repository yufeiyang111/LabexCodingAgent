package com.labex.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/** 记录旧版 reader 的迁移命中和删除门槛；不参与 Agent 运行时状态。 */
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

    public String getReaderKey() { return readerKey; }
    public void setReaderKey(String readerKey) { this.readerKey = readerKey; }
    public String getTargetRemovalVersion() { return targetRemovalVersion; }
    public void setTargetRemovalVersion(String targetRemovalVersion) { this.targetRemovalVersion = targetRemovalVersion; }
    public Integer getObservationWindowDays() { return observationWindowDays; }
    public void setObservationWindowDays(Integer observationWindowDays) { this.observationWindowDays = observationWindowDays; }
    public Long getReadHitCount() { return readHitCount; }
    public void setReadHitCount(Long readHitCount) { this.readHitCount = readHitCount; }
    public Long getSourceItemHitCount() { return sourceItemHitCount; }
    public void setSourceItemHitCount(Long sourceItemHitCount) { this.sourceItemHitCount = sourceItemHitCount; }
    public LocalDateTime getLastReadHitAt() { return lastReadHitAt; }
    public void setLastReadHitAt(LocalDateTime lastReadHitAt) { this.lastReadHitAt = lastReadHitAt; }
    public Long getPendingSourceCount() { return pendingSourceCount; }
    public void setPendingSourceCount(Long pendingSourceCount) { this.pendingSourceCount = pendingSourceCount; }
    public LocalDateTime getLastInventoryAt() { return lastInventoryAt; }
    public void setLastInventoryAt(LocalDateTime lastInventoryAt) { this.lastInventoryAt = lastInventoryAt; }
    public LocalDateTime getZeroInventorySince() { return zeroInventorySince; }
    public void setZeroInventorySince(LocalDateTime zeroInventorySince) { this.zeroInventorySince = zeroInventorySince; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
