CREATE TABLE reference_resolver (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    resolver_code VARCHAR(128) NOT NULL,
    connector_id BIGINT NOT NULL,
    operation_binding_json JSON,
    allowed_auth_profiles_json JSON,
    input_schema_json JSON,
    output_schema_json JSON,
    cache_policy_json JSON,
    staleness_policy_json JSON,
    visibility VARCHAR(16) NOT NULL DEFAULT 'internal',
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_resolver_version (space_id, resolver_code, version),
    KEY idx_resolver_space_status (space_id, status, id)
);

CREATE TABLE business_query_action (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    query_action_code VARCHAR(128) NOT NULL,
    connector_id BIGINT NOT NULL,
    operation_binding_json JSON,
    allowed_auth_profiles_json JSON,
    binding_strategies_json JSON,
    input_schema_json JSON,
    output_schema_json JSON,
    risk_level VARCHAR(16) NOT NULL DEFAULT 'LOW',
    result_visibility_policy_json JSON,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_query_action_version (space_id, query_action_code, version),
    KEY idx_query_action_space_status (space_id, status, id)
);

CREATE TABLE precondition_check (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_id BIGINT NOT NULL,
    check_code VARCHAR(128) NOT NULL,
    connector_id BIGINT NOT NULL,
    operation_binding_json JSON,
    allowed_auth_profiles_json JSON,
    binding_strategies_json JSON,
    input_schema_json JSON,
    check_expression_json JSON,
    failure_policy_json JSON,
    status VARCHAR(16) NOT NULL DEFAULT 'draft',
    version INT NOT NULL DEFAULT 1,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_check_version (space_id, check_code, version),
    KEY idx_check_space_status (space_id, status, id)
);
