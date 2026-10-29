-- ==============================================================================
-- LabexAgent 增量迁移：B1 同步供给（/changes/feed）索引补齐
--
-- 背景：
--   schema.sql 已内联这两个索引，供新库初始化使用；但生产老库不会执行 schema.sql，
--   且 AdditiveSchemaMigrator 在 production 下已禁用，因此必须通过本迁移补齐。
--
-- 影响：
--   仅新增索引，不改列、不改数据。缺失时 /changes/feed 会退化为全表扫描。
--
-- 幂等性：
--   两处均先查 information_schema.statistics，存在则跳过；可重复执行。
-- ==============================================================================

DROP PROCEDURE IF EXISTS upgrade_sync_feed_indexes_20261029;
DELIMITER //

CREATE PROCEDURE upgrade_sync_feed_indexes_20261029()
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 't_agent_change_set'
          AND index_name = 'idx_cs_project_set'
    ) THEN
        ALTER TABLE t_agent_change_set
            ADD INDEX idx_cs_project_set (student_id, project_id, change_set_id);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 't_agent_file_change'
          AND index_name = 'idx_fc_project_update'
    ) THEN
        ALTER TABLE t_agent_file_change
            ADD INDEX idx_fc_project_update (student_id, project_id, update_time);
    END IF;
END //

DELIMITER ;

CALL upgrade_sync_feed_indexes_20261029();
DROP PROCEDURE IF EXISTS upgrade_sync_feed_indexes_20261029;