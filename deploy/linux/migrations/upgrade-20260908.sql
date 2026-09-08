-- ==============================================================================
-- LabexAgent 生产数据库增量安全迁移脚本 (2026-09-08 大迭代专用)
-- 特性：完全幂等。重复执行绝不报错、不破坏已有数据、自动检测字段是否存在。
-- ==============================================================================

DROP PROCEDURE IF EXISTS upgrade_labex_schema_20260908;
DELIMITER //

CREATE PROCEDURE upgrade_labex_schema_20260908()
BEGIN
    -- 1. t_agent_model_config: 扩充模型请求选项 request_options_json
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = DATABASE() AND table_name = 't_agent_model_config' AND column_name = 'request_options_json'
    ) THEN
        ALTER TABLE t_agent_model_config ADD COLUMN request_options_json LONGTEXT DEFAULT NULL;
    END IF;

    -- 2. t_agent_task: 增加父任务关联 parent_task_id (支持子代理体系)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = DATABASE() AND table_name = 't_agent_task' AND column_name = 'parent_task_id'
    ) THEN
        ALTER TABLE t_agent_task ADD COLUMN parent_task_id BIGINT DEFAULT NULL;
    END IF;

    -- 3. t_agent_token_usage: 关联任务与执行 Epoch
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = DATABASE() AND table_name = 't_agent_token_usage' AND column_name = 'task_id'
    ) THEN
        ALTER TABLE t_agent_token_usage ADD COLUMN task_id BIGINT DEFAULT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = DATABASE() AND table_name = 't_agent_token_usage' AND column_name = 'execution_epoch'
    ) THEN
        ALTER TABLE t_agent_token_usage ADD COLUMN execution_epoch BIGINT DEFAULT NULL;
    END IF;

    -- 4. t_agent_subagent: 扩展子代理架构字段 (agent_type, child_task_id, spawn_depth 等)
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = DATABASE() AND table_name = 't_agent_subagent' AND column_name = 'agent_type'
    ) THEN
        ALTER TABLE t_agent_subagent ADD COLUMN agent_type VARCHAR(16) NOT NULL DEFAULT 'general';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = DATABASE() AND table_name = 't_agent_subagent' AND column_name = 'parent_tool_call_id'
    ) THEN
        ALTER TABLE t_agent_subagent ADD COLUMN parent_tool_call_id VARCHAR(160) DEFAULT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = DATABASE() AND table_name = 't_agent_subagent' AND column_name = 'child_task_id'
    ) THEN
        ALTER TABLE t_agent_subagent ADD COLUMN child_task_id BIGINT DEFAULT NULL;
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = DATABASE() AND table_name = 't_agent_subagent' AND column_name = 'spawn_depth'
    ) THEN
        ALTER TABLE t_agent_subagent ADD COLUMN spawn_depth INT NOT NULL DEFAULT 0;
    END IF;

    -- 5. t_agent_run_config_snapshot: 增加请求凭证证据字段 request_evidence_json
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns 
        WHERE table_schema = DATABASE() AND table_name = 't_agent_run_config_snapshot' AND column_name = 'request_evidence_json'
    ) THEN
        ALTER TABLE t_agent_run_config_snapshot ADD COLUMN request_evidence_json LONGTEXT DEFAULT NULL;
    END IF;

    -- 6. 增补对应高频查询复合索引
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics 
        WHERE table_schema = DATABASE() AND table_name = 't_agent_token_usage' AND index_name = 'idx_token_task_epoch'
    ) THEN
        ALTER TABLE t_agent_token_usage ADD INDEX idx_token_task_epoch (task_id, execution_epoch);
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM information_schema.statistics 
        WHERE table_schema = DATABASE() AND table_name = 't_agent_subagent' AND index_name = 'idx_agent_subagent_child_task'
    ) THEN
        ALTER TABLE t_agent_subagent ADD INDEX idx_agent_subagent_child_task (child_task_id);
    END IF;

END //
DELIMITER ;

CALL upgrade_labex_schema_20260908();
DROP PROCEDURE IF EXISTS upgrade_labex_schema_20260908;
