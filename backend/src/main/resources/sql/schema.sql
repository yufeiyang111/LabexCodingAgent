CREATE TABLE IF NOT EXISTS t_user (
    user_id INT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) DEFAULT NULL,
    role VARCHAR(32) DEFAULT 'USER',
    status TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_student_project (
    project_id INT AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL,
    project_name VARCHAR(255) NOT NULL,
    original_file_name VARCHAR(500) DEFAULT NULL,
    archive_path VARCHAR(1000) DEFAULT NULL,
    workspace_path VARCHAR(1000) NOT NULL,
    structure_json LONGTEXT DEFAULT NULL,
    file_count INT DEFAULT 0,
    total_size BIGINT DEFAULT 0,
    status TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_project_student (student_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_model_config (
    config_id INT AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL,
    config_name VARCHAR(100) NOT NULL DEFAULT 'Default',
    provider VARCHAR(50) NOT NULL DEFAULT 'openai_compatible',
    model_name VARCHAR(100) NOT NULL DEFAULT 'gpt-4o-mini',
    api_key VARCHAR(500) NOT NULL DEFAULT '',
    api_key_encrypted VARCHAR(2048) DEFAULT NULL,
    api_key_key_version VARCHAR(64) DEFAULT NULL,
    base_url VARCHAR(300) DEFAULT 'https://api.openai.com',
    max_tokens INT DEFAULT 8192,
    context_window_tokens INT,
    prompt_cache_key_enabled TINYINT NOT NULL DEFAULT 0,
    reasoning_effort VARCHAR(16) NOT NULL DEFAULT 'medium',
    image_input_enabled TINYINT NOT NULL DEFAULT 0,
    compaction_auto TINYINT NOT NULL DEFAULT 1,
    compaction_prune TINYINT NOT NULL DEFAULT 0,
    compaction_tail_turns INT NOT NULL DEFAULT 2,
    compaction_preserve_recent_tokens INT DEFAULT NULL,
    compaction_reserved_tokens INT DEFAULT NULL,
    compaction_model_config_id INT DEFAULT NULL,
    compaction_threshold_percent INT NOT NULL DEFAULT 90,
    temperature DOUBLE DEFAULT NULL,
    is_default TINYINT DEFAULT 0,
    status TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_student_id (student_id),
    INDEX idx_student_default (student_id, is_default, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_conversation (
    conversation_id VARCHAR(64) NOT NULL PRIMARY KEY,
    student_id INT NOT NULL,
    project_id INT NOT NULL,
    title VARCHAR(500) DEFAULT NULL,
    mode VARCHAR(32) DEFAULT 'agent',
    provider VARCHAR(64) DEFAULT NULL,
    model VARCHAR(128) DEFAULT NULL,
    summary TEXT DEFAULT NULL,
    parent_conversation_id VARCHAR(64) DEFAULT NULL,
    forked_from_message_id BIGINT DEFAULT NULL,
    compacted_at DATETIME DEFAULT NULL,
    status INT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_conv_student (student_id),
    INDEX idx_conv_project (project_id),
    INDEX idx_agent_conversation_parent (parent_conversation_id),
    INDEX idx_conv_project_updated (student_id, project_id, status, update_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_task (
    task_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(128) DEFAULT NULL,
    student_id INT NOT NULL,
    project_id INT NOT NULL,
    title VARCHAR(500) DEFAULT NULL,
    mode VARCHAR(32) DEFAULT 'agent',
    status VARCHAR(32) DEFAULT 'pending',
    current_step VARCHAR(500) DEFAULT NULL,
    summary TEXT DEFAULT NULL,
    run_version BIGINT NOT NULL DEFAULT 0,
    last_event_sequence BIGINT NOT NULL DEFAULT 0,
    request_payload LONGTEXT DEFAULT NULL,
    recovery_attempts INT NOT NULL DEFAULT 0,
    retry_attempts INT NOT NULL DEFAULT 0,
    next_retry_at DATETIME(3) DEFAULT NULL,
    execution_epoch BIGINT NOT NULL DEFAULT 0,
    execution_owner VARCHAR(128) DEFAULT NULL,
    execution_lease_expires_at DATETIME(3) DEFAULT NULL,
    execution_heartbeat_at DATETIME(3) DEFAULT NULL,
    background_branch VARCHAR(160) DEFAULT NULL,
    background_worktree VARCHAR(2048) DEFAULT NULL,
    background_base_ref VARCHAR(128) DEFAULT NULL,
    background_cleanup_status VARCHAR(32) DEFAULT NULL,
    submitted_at DATETIME(3) DEFAULT NULL,
    started_at DATETIME(3) DEFAULT NULL,
    active_segment_started_at DATETIME(3) DEFAULT NULL,
    finished_at DATETIME(3) DEFAULT NULL,
    elapsed_ms BIGINT DEFAULT NULL,
    active_elapsed_ms BIGINT NOT NULL DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_task_conv (conversation_id),
    INDEX idx_task_student (student_id),
    INDEX idx_task_project (project_id),
    INDEX idx_task_retry_due (status, next_retry_at),
    INDEX idx_task_execution_lease (execution_lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_message (
    message_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    student_id INT NOT NULL,
    project_id INT NOT NULL,
    event_type VARCHAR(32) DEFAULT NULL,
    role VARCHAR(32) DEFAULT NULL,
    content LONGTEXT DEFAULT NULL,
    event_data LONGTEXT DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_msg_conv (conversation_id),
    INDEX idx_msg_student (student_id),
    INDEX idx_msg_conversation_history (conversation_id, student_id, project_id, message_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_change_set (
    change_set_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    student_id INT NOT NULL,
    project_id INT NOT NULL,
    status VARCHAR(32) DEFAULT 'pending',
    change_count INT DEFAULT 0,
    summary TEXT DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_cs_task (task_id),
    INDEX idx_cs_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_file_change (
    change_id VARCHAR(64) NOT NULL PRIMARY KEY,
    change_set_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) NOT NULL,
    student_id INT NOT NULL,
    project_id INT NOT NULL,
    relative_path VARCHAR(1000) NOT NULL,
    change_type VARCHAR(32) DEFAULT 'modify',
    before_hash VARCHAR(128) DEFAULT NULL,
    before_content LONGTEXT DEFAULT NULL,
    after_content LONGTEXT DEFAULT NULL,
    diff_text LONGTEXT DEFAULT NULL,
    snapshot_before_ref VARCHAR(64) DEFAULT NULL,
    snapshot_after_ref VARCHAR(64) DEFAULT NULL,
    snapshot_paths MEDIUMTEXT DEFAULT NULL,
    snapshot_status VARCHAR(32) DEFAULT NULL,
    status VARCHAR(32) DEFAULT 'pending',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    applied_time DATETIME DEFAULT NULL,
    rejected_time DATETIME DEFAULT NULL,
    undone_time DATETIME DEFAULT NULL,
    INDEX idx_fc_cs (change_set_id),
    INDEX idx_fc_task (task_id),
    INDEX idx_fc_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_verification (
    verification_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    change_set_id BIGINT NOT NULL,
    student_id INT NOT NULL,
    project_id INT NOT NULL,
    command VARCHAR(2000) DEFAULT NULL,
    status VARCHAR(32) DEFAULT 'pending',
    exit_code INT DEFAULT NULL,
    output LONGTEXT DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_ver_task (task_id),
    INDEX idx_ver_cs (change_set_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_token_usage (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id VARCHAR(64) NOT NULL,
    session_id VARCHAR(128) DEFAULT NULL,
    student_id INT NOT NULL,
    project_id INT NOT NULL,
    provider VARCHAR(64) DEFAULT NULL,
    model VARCHAR(128) DEFAULT NULL,
    prompt_tokens INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    total_tokens INT DEFAULT 0,
    cached_tokens INT DEFAULT 0,
    cache_write_tokens INT DEFAULT 0,
    iteration INT DEFAULT 0,
    tool_name VARCHAR(64) DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_token_conv (conversation_id),
    INDEX idx_token_student (student_id),
    INDEX idx_token_project (project_id),
    INDEX idx_token_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_skill (
    skill_id INT AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL,
    skill_key VARCHAR(80) NOT NULL,
    title VARCHAR(120) NOT NULL,
    description VARCHAR(500) DEFAULT NULL,
    content LONGTEXT NOT NULL,
    is_enabled TINYINT DEFAULT 1,
    status TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_skill_student_key (student_id, skill_key),
    INDEX idx_agent_skill_student (student_id, status, is_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_mcp_server (
    server_id INT AUTO_INCREMENT PRIMARY KEY,
    student_id INT NOT NULL,
    server_key VARCHAR(80) NOT NULL,
    server_name VARCHAR(120) NOT NULL,
    transport VARCHAR(32) DEFAULT 'http',
    endpoint VARCHAR(500) NOT NULL,
    auth_header VARCHAR(600) DEFAULT NULL,
    auth_header_encrypted VARCHAR(2048) DEFAULT NULL,
    auth_header_key_version VARCHAR(64) DEFAULT NULL,
    tools_json LONGTEXT DEFAULT NULL,
    is_enabled TINYINT DEFAULT 1,
    status TINYINT DEFAULT 1,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_mcp_student_key (student_id, server_key),
    INDEX idx_agent_mcp_student (student_id, status, is_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_permission_approval (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    project_id INT NOT NULL,
    permission VARCHAR(80) NOT NULL,
    pattern VARCHAR(1000) NOT NULL,
    created_at DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_permission_approval (project_id, permission, pattern(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_evaluation_run (
    evaluation_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    corpus_version VARCHAR(64) NOT NULL,
    candidate VARCHAR(256) NOT NULL,
    total INT NOT NULL,
    passed INT NOT NULL,
    accepted_changes INT NOT NULL DEFAULT 0,
    cost_micros BIGINT NOT NULL DEFAULT 0,
    latency_millis BIGINT NOT NULL DEFAULT 0,
    safety_violations INT NOT NULL DEFAULT 0,
    human_corrections INT NOT NULL DEFAULT 0,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_agent_evaluation_candidate (corpus_version, candidate, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_subagent_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    subagent_id BIGINT NOT NULL,
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(64) NOT NULL,
    payload LONGTEXT DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_subagent_event_sequence (subagent_id, sequence_no),
    INDEX idx_agent_subagent_event_replay (subagent_id, event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_subagent (
    subagent_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    parent_subagent_id BIGINT DEFAULT NULL,
    identity VARCHAR(128) NOT NULL,
    instructions LONGTEXT NOT NULL,
    model_config_id INT DEFAULT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'queued',
    token_budget INT NOT NULL,
    tokens_used INT NOT NULL DEFAULT 0,
    permissions_json LONGTEXT DEFAULT NULL,
    tools_json LONGTEXT DEFAULT NULL,
    background TINYINT NOT NULL DEFAULT 0,
    summary TEXT DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_agent_subagent_task_status (task_id, status),
    INDEX idx_agent_subagent_parent (parent_subagent_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_run_artifact (
    artifact_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    artifact_type VARCHAR(64) NOT NULL,
    artifact_path VARCHAR(2048) DEFAULT NULL,
    content LONGTEXT DEFAULT NULL,
    sha256 VARCHAR(64) DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_agent_run_artifact_task (task_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_run_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id BIGINT NOT NULL,
    student_id INT NOT NULL,
    project_id INT NOT NULL,
    sequence_number BIGINT NOT NULL,
    state VARCHAR(32) NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    payload LONGTEXT DEFAULT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_run_event_sequence (task_id, sequence_number),
    UNIQUE KEY uk_agent_run_event_idempotency (task_id, idempotency_key),
    INDEX idx_agent_run_event_task_sequence (task_id, sequence_number),
    INDEX idx_agent_run_event_project (project_id, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_run_outbox (
    outbox_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id BIGINT NOT NULL,
    task_id BIGINT NOT NULL,
    topic VARCHAR(80) NOT NULL,
    payload LONGTEXT NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    attempts INT NOT NULL DEFAULT 0,
    available_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_time DATETIME DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_run_outbox_event (event_id),
    INDEX idx_agent_run_outbox_pending (status, available_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_agent_run_interaction (
    interaction_id VARCHAR(64) NOT NULL PRIMARY KEY,
    task_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) DEFAULT NULL,
    session_id VARCHAR(128) DEFAULT NULL,
    student_id INT NOT NULL,
    project_id INT NOT NULL,
    interaction_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'waiting',
    request_payload LONGTEXT DEFAULT NULL,
    response_payload LONGTEXT DEFAULT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    expires_time DATETIME DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_run_interaction_idempotency (task_id, idempotency_key),
    INDEX idx_agent_run_interaction_task_status (task_id, status),
    INDEX idx_agent_run_interaction_owner (student_id, project_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_command_approval (
    approval_id VARCHAR(64) NOT NULL PRIMARY KEY,
    idempotency_key VARCHAR(128) NOT NULL,
    student_id INT NOT NULL,
    project_id INT NOT NULL,
    task_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) DEFAULT NULL,
    session_id VARCHAR(128) DEFAULT NULL,
    source VARCHAR(64) NOT NULL,
    invocation_id VARCHAR(128) NOT NULL,
    tool_call_id VARCHAR(128) DEFAULT NULL,
    command_digest VARCHAR(64) NOT NULL,
    canonical_command LONGTEXT NOT NULL,
    display_command TEXT NOT NULL,
    working_directory VARCHAR(2048) NOT NULL,
    shell VARCHAR(64) NOT NULL,
    command_options LONGTEXT NOT NULL,
    classification VARCHAR(64) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'pending',
    decision_idempotency_key VARCHAR(128) DEFAULT NULL,
    decision_time DATETIME DEFAULT NULL,
    decided_by_student_id INT DEFAULT NULL,
    expires_time DATETIME NOT NULL,
    consumed_time DATETIME DEFAULT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_command_approval_owner_idempotency (student_id, project_id, idempotency_key),
    INDEX idx_command_approval_owner_status (student_id, project_id, status),
    INDEX idx_command_approval_task_status (task_id, status),
    INDEX idx_command_approval_expiry (status, expires_time),
    INDEX idx_command_approval_invocation (invocation_id, tool_call_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS t_command_audit_event (
    event_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    approval_id VARCHAR(64) NOT NULL,
    student_id INT NOT NULL,
    project_id INT NOT NULL,
    task_id BIGINT NOT NULL,
    conversation_id VARCHAR(64) DEFAULT NULL,
    session_id VARCHAR(128) DEFAULT NULL,
    event_type VARCHAR(48) NOT NULL,
    source VARCHAR(64) NOT NULL,
    invocation_id VARCHAR(128) NOT NULL,
    tool_call_id VARCHAR(128) NOT NULL,
    command_digest VARCHAR(64) NOT NULL,
    classification VARCHAR(64) NOT NULL,
    policy_version VARCHAR(64) NOT NULL,
    decision VARCHAR(32) DEFAULT NULL,
    execution_status VARCHAR(32) DEFAULT NULL,
    exit_code INT DEFAULT NULL,
    duration_ms BIGINT DEFAULT NULL,
    output_digest VARCHAR(64) DEFAULT NULL,
    output_size_bytes BIGINT DEFAULT NULL,
    idempotency_key VARCHAR(192) NOT NULL,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_command_audit_approval_idempotency (approval_id, idempotency_key),
    INDEX idx_command_audit_task (task_id, event_id),
    INDEX idx_command_audit_owner (student_id, project_id, create_time),
    INDEX idx_command_audit_approval_type (approval_id, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
