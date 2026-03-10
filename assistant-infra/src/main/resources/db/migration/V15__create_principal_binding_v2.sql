CREATE TABLE principal_binding_v2 (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    connector_id BIGINT NOT NULL,
    platform_principal_id VARCHAR(128) NOT NULL,
    platform_principal_type VARCHAR(32) NOT NULL DEFAULT 'user',
    target_principal_type VARCHAR(32) NOT NULL,
    target_principal_id VARCHAR(128) NOT NULL,
    scope_constraints_json JSON,
    priority INT NOT NULL DEFAULT 100,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_connector_platform_principal (space_id, connector_id, platform_principal_id),
    KEY idx_binding_space_connector_status (space_id, connector_id, status, priority)
);
