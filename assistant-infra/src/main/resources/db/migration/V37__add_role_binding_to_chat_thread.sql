ALTER TABLE chat_thread
    ADD COLUMN role_package_code VARCHAR(128) NULL COMMENT '岗位包编码' AFTER tool_code,
    ADD COLUMN role_package_version VARCHAR(64) NULL COMMENT '岗位包版本' AFTER role_package_code,
    ADD COLUMN role_scenario_code VARCHAR(128) NULL COMMENT '岗位场景编码' AFTER role_package_version;

CREATE INDEX idx_chat_thread_assistant_role ON chat_thread (assistant_uid, role_package_code, updated_at);
