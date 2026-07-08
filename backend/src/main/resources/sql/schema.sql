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
    api_key VARCHAR(500) NOT NULL,
    base_url VARCHAR(300) DEFAULT 'https://api.openai.com',
    max_tokens INT DEFAULT 8192,
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
    INDEX idx_agent_conversation_parent (parent_conversation_id)
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
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_task_conv (conversation_id),
    INDEX idx_task_student (student_id),
    INDEX idx_task_project (project_id)
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
    INDEX idx_msg_student (student_id)
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
