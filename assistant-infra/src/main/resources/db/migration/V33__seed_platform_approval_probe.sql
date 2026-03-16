-- 注册平台审批探针工具，用于真实验证 WAITING_APPROVAL -> 审批决定 -> 恢复执行 链路。
-- 该工具走 canonical tool_meta + executionPlan.approvalGate，不依赖旧审批策略字段。

INSERT INTO tool_meta (
    tenant_id,
    tool_code,
    tool_name,
    description,
    system_code,
    api_endpoint,
    http_method,
    content_type,
    parameter_schema,
    execution_plan,
    interaction_policy,
    risk_level,
    requires_auth,
    requires_confirm,
    capability_type,
    version,
    status
) VALUES (
    'default',
    'gougu_oa.platform_approval_probe',
    '平台审批探针',
    '用于验证平台 WAITING_APPROVAL、审批队列与恢复执行链路的运维诊断工具。',
    'gougu_oa',
    '/platform/approval-probe',
    'POST',
    'application/json',
    '{
      "slots": [
        {
          "name": "approval_note",
          "type": "string",
          "title": "审批说明",
          "aiHint": "请输入本次平台审批探针的说明，用于区分不同验证批次。",
          "askMode": "BATCH",
          "required": true,
          "priority": "CORE",
          "uiComponent": "textarea",
          "displayConfig": {
            "summaryGroup": "CORE",
            "summaryOrder": 1,
            "showInSummary": true
          }
        }
      ]
    }',
    '{"version":"2.0","entry":["approval_probe"],"terminal":["approval_probe"],"steps":{"approval_probe":{"stepId":"approval_probe","name":"平台审批探针","type":"CONDITION","config":{"conditions":{"enabled":true,"expression":true},"approvalGate":{"channel":"platform","title":"平台审批探针","reason":"验证平台 WAITING_APPROVAL 恢复链路"}}}}}',
    '{"toolType":"WORKFLOW","visibility":"USER","invocationPolicy":"DIRECT","executionMode":"SYNC","keywords":["平台审批探针","审批探针","WAITING_APPROVAL","审批恢复"]}',
    'HIGH',
    1,
    1,
    'WRITE',
    1,
    'enabled'
)
ON DUPLICATE KEY UPDATE
    tool_name = VALUES(tool_name),
    description = VALUES(description),
    system_code = VALUES(system_code),
    api_endpoint = VALUES(api_endpoint),
    http_method = VALUES(http_method),
    content_type = VALUES(content_type),
    parameter_schema = VALUES(parameter_schema),
    execution_plan = VALUES(execution_plan),
    interaction_policy = VALUES(interaction_policy),
    risk_level = VALUES(risk_level),
    requires_auth = VALUES(requires_auth),
    requires_confirm = VALUES(requires_confirm),
    capability_type = VALUES(capability_type),
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;
