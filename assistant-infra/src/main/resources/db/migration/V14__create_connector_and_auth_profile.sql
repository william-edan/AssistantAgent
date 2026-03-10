CREATE TABLE connector (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    connector_code VARCHAR(64) NOT NULL,
    system_code VARCHAR(64) DEFAULT NULL,
    display_name VARCHAR(128) NOT NULL,
    protocol_type VARCHAR(32) NOT NULL,
    environment VARCHAR(16) NOT NULL DEFAULT 'prod',
    network_zone VARCHAR(32) NOT NULL DEFAULT 'intranet',
    base_url VARCHAR(512) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_connector_version (space_id, connector_code, version),
    KEY idx_connector_space_status (space_id, status, id)
);

CREATE TABLE auth_profile (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    connector_id BIGINT NOT NULL,
    auth_profile_code VARCHAR(64) NOT NULL,
    auth_type VARCHAR(32) NOT NULL,
    usage_policy VARCHAR(32) NOT NULL DEFAULT 'read_only',
    token_endpoint VARCHAR(512),
    token_header_name VARCHAR(64) DEFAULT 'Authorization',
    token_header_prefix VARCHAR(64) DEFAULT 'Bearer ',
    audience VARCHAR(256),
    scopes_json JSON,
    credential_ref VARCHAR(256),
    refresh_policy_json JSON,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_connector_auth_profile (connector_id, auth_profile_code),
    KEY idx_auth_profile_space_status (space_id, status, id)
);
