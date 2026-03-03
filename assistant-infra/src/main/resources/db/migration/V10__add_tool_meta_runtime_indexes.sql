CREATE INDEX idx_tool_meta_tenant_status_id
ON tool_meta (tenant_id, status, id);

CREATE INDEX idx_tool_meta_tenant_system_status_id
ON tool_meta (tenant_id, system_code, status, id);
