CREATE TABLE agent_app (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    agent_app_code VARCHAR(128) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    prompt_policy_json JSON,
    memory_policy_json JSON,
    approval_strategy_json JSON,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_agent_app (space_id, agent_app_code)
);

CREATE TABLE agent_app_grant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    agent_app_id BIGINT NOT NULL,
    target_type VARCHAR(32) NOT NULL,
    target_code VARCHAR(128) NOT NULL,
    grant_mode VARCHAR(16) NOT NULL DEFAULT 'allow',
    constraints_json JSON,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_agent_app_grant (agent_app_id, target_type, target_code)
);
