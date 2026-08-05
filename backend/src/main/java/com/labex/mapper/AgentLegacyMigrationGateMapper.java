package com.labex.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.labex.entity.AgentLegacyMigrationGate;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AgentLegacyMigrationGateMapper extends BaseMapper<AgentLegacyMigrationGate> {
    @Select("SELECT * FROM t_agent_legacy_migration_gate WHERE reader_key = #{readerKey} FOR UPDATE")
    AgentLegacyMigrationGate selectForUpdate(@Param("readerKey") String readerKey);
    @Update("UPDATE t_agent_legacy_migration_gate SET "
            + "target_removal_version = #{targetRemovalVersion}, "
            + "observation_window_days = #{observationWindowDays}, "
            + "read_hit_count = #{readHitCount}, "
            + "source_item_hit_count = #{sourceItemHitCount}, "
            + "last_read_hit_at = #{lastReadHitAt}, "
            + "pending_source_count = #{pendingSourceCount}, "
            + "last_inventory_at = #{lastInventoryAt}, "
            + "zero_inventory_since = #{zeroInventorySince}, "
            + "update_time = #{updateTime} WHERE reader_key = #{readerKey}")
    int updateGate(AgentLegacyMigrationGate gate);
}
