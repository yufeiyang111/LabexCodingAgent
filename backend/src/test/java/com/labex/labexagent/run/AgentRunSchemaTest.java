package com.labex.labexagent.run;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class AgentRunSchemaTest {

    @Test
    void definesAdditiveRunEventOutboxAndInteractionStorage() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/sql/schema.sql"));

        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS t_agent_run_event"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS t_agent_run_artifact"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS t_agent_subagent"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS t_agent_subagent_event"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS t_agent_evaluation_run"));
        assertTrue(schema.contains("idx_agent_evaluation_candidate"));
        assertTrue(schema.contains("uk_agent_subagent_event_sequence"));
        assertTrue(schema.contains("idx_agent_subagent_task_status"));
        assertTrue(schema.contains("instructions LONGTEXT NOT NULL"));
        assertTrue(schema.contains("model_config_id INT DEFAULT NULL"));
        assertTrue(schema.contains("idx_agent_run_artifact_task"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS t_agent_run_outbox"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS t_agent_run_interaction"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS t_command_approval"));
        assertTrue(schema.contains("CREATE TABLE IF NOT EXISTS t_command_audit_event"));
        assertTrue(schema.contains("uk_command_audit_approval_idempotency"));
        assertTrue(schema.contains("output_digest VARCHAR(64) DEFAULT NULL"));
        assertTrue(schema.contains("uk_command_approval_owner_idempotency"));
        assertTrue(schema.contains("idx_command_approval_owner_status"));
        assertTrue(schema.contains("idx_command_approval_expiry"));
        assertTrue(schema.contains("command_digest VARCHAR(64) NOT NULL"));
        assertTrue(schema.contains("consumed_time DATETIME DEFAULT NULL"));
        assertTrue(schema.contains("uk_agent_run_event_sequence"));
        assertTrue(schema.contains("uk_agent_run_event_idempotency"));
        assertTrue(schema.contains("last_event_sequence BIGINT NOT NULL DEFAULT 0"));
        assertTrue(schema.contains("background_branch VARCHAR(160) DEFAULT NULL"));
        assertTrue(schema.contains("background_worktree VARCHAR(2048) DEFAULT NULL"));
        assertTrue(schema.contains("background_base_ref VARCHAR(128) DEFAULT NULL"));
        assertTrue(schema.contains("background_cleanup_status VARCHAR(32) DEFAULT NULL"));
        assertTrue(schema.contains("forked_from_task_id BIGINT DEFAULT NULL"));
        assertTrue(schema.contains("history_projection_version VARCHAR(32) DEFAULT NULL"));
        assertTrue(schema.contains("history_migrated_at DATETIME(3) DEFAULT NULL"));
        assertTrue(schema.contains("idx_agent_conversation_fork_task"));
    }

    @Test
    void keepsTheBootstrapSchemaCompatibleWithPre829MySqlServers() throws Exception {
        String schema = Files.readString(Path.of("src/main/resources/sql/schema.sql"));

        assertTrue(schema.contains("run_version BIGINT NOT NULL DEFAULT 0"));
        assertTrue(schema.contains("last_event_sequence BIGINT NOT NULL DEFAULT 0"));
        assertTrue(schema.contains("background_branch VARCHAR(160) DEFAULT NULL"));
        assertTrue(schema.contains("background_worktree VARCHAR(2048) DEFAULT NULL"));
        assertTrue(schema.contains("background_base_ref VARCHAR(128) DEFAULT NULL"));
        assertTrue(schema.contains("background_cleanup_status VARCHAR(32) DEFAULT NULL"));
        assertTrue(!schema.contains("ADD COLUMN IF NOT EXISTS"));
    }
}
