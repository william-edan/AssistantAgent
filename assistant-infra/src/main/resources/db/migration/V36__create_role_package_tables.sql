CREATE TABLE role_package (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    agent_app_code VARCHAR(128) NOT NULL,
    role_code VARCHAR(128) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    persona TEXT,
    version VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    published_at DATETIME,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_role_package_version (space_id, agent_app_code, role_code, version),
    KEY idx_role_package_published (space_id, agent_app_code, role_code, status)
);

CREATE TABLE role_scenario (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_package_id BIGINT NOT NULL,
    scenario_code VARCHAR(128) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    description VARCHAR(512),
    routing_hints_json JSON,
    sort_order INT NOT NULL DEFAULT 0,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_role_scenario_role_package (role_package_id, sort_order)
);

CREATE TABLE role_tool_scope (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_package_id BIGINT NOT NULL,
    scenario_code VARCHAR(128),
    tool_code VARCHAR(128) NOT NULL,
    scope_mode VARCHAR(16) NOT NULL DEFAULT 'OPTIONAL',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_role_tool_scope_role_package (role_package_id)
);

CREATE TABLE role_proactive_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_package_id BIGINT NOT NULL,
    task_code VARCHAR(128) NOT NULL,
    cron_expr VARCHAR(128) NOT NULL,
    artifact_code VARCHAR(128) NOT NULL,
    scenario_code VARCHAR(128),
    task_payload_json JSON,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_role_proactive_task_role_package (role_package_id)
);

CREATE TABLE role_kpi_metric (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    role_package_id BIGINT NOT NULL,
    metric_code VARCHAR(128) NOT NULL,
    display_name VARCHAR(256) NOT NULL,
    target_value VARCHAR(128),
    metric_definition_json JSON,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    KEY idx_role_kpi_metric_role_package (role_package_id)
);
