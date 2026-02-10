CREATE TABLE IF NOT EXISTS assistant_capability (
  id BIGINT PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  capability_id VARCHAR(128) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  domain_code VARCHAR(64) NOT NULL,
  latest_version INT NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_tenant_capability UNIQUE (tenant_id, capability_id, deleted)
);

CREATE TABLE IF NOT EXISTS assistant_capability_version (
  id BIGINT PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  capability_id VARCHAR(128) NOT NULL,
  version_no INT NOT NULL,
  connector_id BIGINT NOT NULL,
  input_schema_json CLOB NOT NULL,
  output_schema_json CLOB NOT NULL,
  slot_schema_json CLOB,
  tool_binding_json CLOB,
  route_config_json CLOB,
  execution_mode VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_tenant_capability_version UNIQUE (tenant_id, capability_id, version_no, deleted)
);

CREATE TABLE IF NOT EXISTS assistant_conversation_session (
  id BIGINT PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  session_id VARCHAR(128) NOT NULL,
  capability_id VARCHAR(128) NOT NULL,
  capability_version INT NOT NULL,
  slot_snapshot_json CLOB NOT NULL,
  session_status VARCHAR(32) NOT NULL,
  last_error_code VARCHAR(64),
  last_error_message VARCHAR(1024),
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_conversation_session UNIQUE (tenant_id, session_id, capability_id, deleted)
);

CREATE TABLE IF NOT EXISTS assistant_action_execution (
  id BIGINT PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  session_id VARCHAR(128) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  capability_id VARCHAR(128) NOT NULL,
  capability_version INT NOT NULL,
  executor_user_id VARCHAR(64) NOT NULL,
  execution_mode VARCHAR(32) NOT NULL,
  input_json CLOB NOT NULL,
  output_json CLOB,
  status VARCHAR(32) NOT NULL,
  error_code VARCHAR(64),
  error_message VARCHAR(1024),
  cost_ms BIGINT NOT NULL DEFAULT 0,
  created_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_execution_request UNIQUE (tenant_id, request_id)
);

CREATE TABLE IF NOT EXISTS assistant_user_binding (
  id BIGINT PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  platform_user_id VARCHAR(64) NOT NULL,
  system_code VARCHAR(64) NOT NULL,
  external_user_id VARCHAR(128) NOT NULL,
  binding_mode VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_user_binding UNIQUE (tenant_id, platform_user_id, system_code, deleted)
);

CREATE TABLE IF NOT EXISTS assistant_connector (
  id BIGINT PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  connector_code VARCHAR(64) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  connector_type VARCHAR(32) NOT NULL,
  base_url VARCHAR(512) NOT NULL,
  status VARCHAR(32) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_connector_code UNIQUE (tenant_id, connector_code, deleted)
);

CREATE TABLE IF NOT EXISTS assistant_connector_auth (
  id BIGINT PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  connector_id BIGINT NOT NULL,
  auth_type VARCHAR(32) NOT NULL,
  auth_config_json CLOB NOT NULL,
  status VARCHAR(32) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_connector_auth UNIQUE (tenant_id, connector_id, deleted)
);

CREATE TABLE IF NOT EXISTS assistant_connector_api (
  id BIGINT PRIMARY KEY,
  tenant_id VARCHAR(64) NOT NULL,
  connector_id BIGINT NOT NULL,
  api_code VARCHAR(128) NOT NULL,
  display_name VARCHAR(128) NOT NULL,
  http_method VARCHAR(16) NOT NULL,
  path_template VARCHAR(512) NOT NULL,
  request_schema_json CLOB NOT NULL,
  response_schema_json CLOB NOT NULL,
  status VARCHAR(32) NOT NULL,
  deleted TINYINT NOT NULL DEFAULT 0,
  created_by VARCHAR(64) NOT NULL,
  created_at TIMESTAMP NOT NULL,
  updated_by VARCHAR(64) NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  CONSTRAINT uk_connector_api UNIQUE (tenant_id, connector_id, api_code, deleted)
);
