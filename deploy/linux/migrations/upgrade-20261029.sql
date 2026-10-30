-- ==============================================================================
-- LabexAgent 增量迁移：同步供给和实测热点查询索引调整
--
-- 背景：
--   schema.sql 负责新库基线；生产老库不会执行 schema.sql，且生产禁用启动期迁移器，
--   因此新增索引和冗余索引清理由本迁移受控执行。
--
-- 影响：
--   仅调整索引，不改列和业务数据。
--
-- 幂等性：
--   新增索引和待清理索引均先查 information_schema.statistics；可重复执行。
-- ==============================================================================

DROP PROCEDURE IF EXISTS upgrade_database_hot_indexes_20261029;
DELIMITER //

CREATE PROCEDURE upgrade_database_hot_indexes_20261029()
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

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 't_agent_task'
          AND index_name = 'idx_agent_task_student_status'
    ) THEN
        ALTER TABLE t_agent_task
            ADD INDEX idx_agent_task_student_status (student_id, status);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 't_agent_run_event'
          AND index_name = 'idx_agent_run_event_task_type'
    ) THEN
        ALTER TABLE t_agent_run_event
            ADD INDEX idx_agent_run_event_task_type (task_id, event_type);
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 't_agent_run_event'
          AND index_name = 'idx_agent_run_event_task_sequence'
    ) THEN
        ALTER TABLE t_agent_run_event
            DROP INDEX idx_agent_run_event_task_sequence;
    END IF;

    IF EXISTS (
        SELECT 1 FROM information_schema.statistics
        WHERE table_schema = DATABASE()
          AND table_name = 't_ops_metric_sample'
          AND index_name = 'idx_ops_metric_sample_time'
    ) THEN
        ALTER TABLE t_ops_metric_sample
            DROP INDEX idx_ops_metric_sample_time;
    END IF;
END //

DELIMITER ;

CALL upgrade_database_hot_indexes_20261029();
DROP PROCEDURE IF EXISTS upgrade_database_hot_indexes_20261029;
