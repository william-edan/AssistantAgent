-- Seed digital admin agent app and published role package for migration profile.

INSERT INTO agent_app (
    space_id,
    agent_app_code,
    display_name,
    prompt_policy_json,
    memory_policy_json,
    approval_strategy_json,
    status
)
SELECT
    ps.id,
    'admin-agent',
    '数字行政助理应用',
    JSON_OBJECT('style', 'role_package_first', 'defaultRolePackageCode', 'digital-admin'),
    JSON_OBJECT('mode', 'bounded', 'maxTurns', 20),
    JSON_OBJECT('channel', 'platform', 'defaultRequired', FALSE),
    'active'
FROM platform_space ps
WHERE ps.space_code = 'default'
  AND ps.environment = 'prod'
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    prompt_policy_json = VALUES(prompt_policy_json),
    memory_policy_json = VALUES(memory_policy_json),
    approval_strategy_json = VALUES(approval_strategy_json),
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO agent_app_grant (agent_app_id, target_type, target_code, grant_mode, constraints_json)
SELECT
    aa.id,
    'publication_source',
    'tool-meta-catalog',
    'allow',
    NULL
FROM agent_app aa
WHERE aa.agent_app_code = 'admin-agent'
  AND NOT EXISTS (
      SELECT 1
      FROM agent_app_grant ag
      WHERE ag.agent_app_id = aa.id
        AND ag.target_type = 'publication_source'
        AND ag.target_code = 'tool-meta-catalog'
  );

INSERT INTO agent_app_grant (agent_app_id, target_type, target_code, grant_mode, constraints_json)
SELECT
    aa.id,
    'publication_source_policy',
    'default',
    'allow',
    JSON_OBJECT('sourceSelectionMode', 'EXCLUSIVE')
FROM agent_app aa
WHERE aa.agent_app_code = 'admin-agent'
  AND NOT EXISTS (
      SELECT 1
      FROM agent_app_grant ag
      WHERE ag.agent_app_id = aa.id
        AND ag.target_type = 'publication_source_policy'
        AND ag.target_code = 'default'
  );

INSERT INTO role_package (
    space_id,
    agent_app_code,
    role_code,
    display_name,
    persona,
    version,
    status,
    published_at
)
SELECT
    ps.id,
    'admin-agent',
    'digital-admin',
    '数字行政助理',
    '负责会议协调、周报收集、审批清理和值班请假协同的数字员工岗位包。',
    'v1',
    'published',
    CURRENT_TIMESTAMP
FROM platform_space ps
WHERE ps.space_code = 'default'
  AND ps.environment = 'prod'
ON DUPLICATE KEY UPDATE
    display_name = VALUES(display_name),
    persona = VALUES(persona),
    status = VALUES(status),
    published_at = VALUES(published_at),
    updated_at = CURRENT_TIMESTAMP;

DELETE FROM role_scenario
WHERE role_package_id IN (
    SELECT id FROM role_package
    WHERE agent_app_code = 'admin-agent'
      AND role_code = 'digital-admin'
      AND version = 'v1'
);

INSERT INTO role_scenario (
    role_package_id,
    scenario_code,
    display_name,
    description,
    routing_hints_json,
    sort_order
)
SELECT rp.id, 'meeting_coordination', '会议协调', '围绕会议室、参会人和会议需求完成协同。', JSON_OBJECT('intent', 'meeting', 'keywords', JSON_ARRAY('会议', '会议室', '排期')), 0
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'weekly_report_collection', '周报收集', '围绕周报类型、收件人和汇报节奏完成收集。', JSON_OBJECT('intent', 'weekly_report', 'keywords', JSON_ARRAY('周报', '汇报', '收集')), 1
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'approval_cleanup', '审批清理', '围绕审批流程、待办清理和升级通知完成闭环。', JSON_OBJECT('intent', 'approval_cleanup', 'keywords', JSON_ARRAY('审批', '清理', '流程')), 2
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'leave_duty_coordination', '请假值班协调', '围绕请假类型、审批人和值班交接完成协同。', JSON_OBJECT('intent', 'leave_duty', 'keywords', JSON_ARRAY('请假', '值班', '交接')), 3
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1';

DELETE FROM role_tool_scope
WHERE role_package_id IN (
    SELECT id FROM role_package
    WHERE agent_app_code = 'admin-agent'
      AND role_code = 'digital-admin'
      AND version = 'v1'
);

INSERT INTO role_tool_scope (role_package_id, scenario_code, tool_code, scope_mode)
SELECT rp.id, 'meeting_coordination', 'office1.meeting_rooms', 'REQUIRED'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'meeting_coordination', 'office1.meeting_requirements', 'OPTIONAL'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'meeting_coordination', 'office1.employees', 'REQUIRED'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'weekly_report_collection', 'office1.work_types', 'REQUIRED'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'weekly_report_collection', 'office1.employees', 'REQUIRED'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'approval_cleanup', 'office1.approval_flows', 'REQUIRED'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'approval_cleanup', 'office1.my_leader', 'OPTIONAL'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'leave_duty_coordination', 'office1.leave_types', 'REQUIRED'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'leave_duty_coordination', 'office1.approval_flows', 'REQUIRED'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'leave_duty_coordination', 'office1.my_leader', 'REQUIRED'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1';

DELETE FROM role_proactive_task
WHERE role_package_id IN (
    SELECT id FROM role_package
    WHERE agent_app_code = 'admin-agent'
      AND role_code = 'digital-admin'
      AND version = 'v1'
);

INSERT INTO role_proactive_task (
    role_package_id,
    task_code,
    cron_expr,
    artifact_code,
    scenario_code,
    task_payload_json,
    status
)
SELECT rp.id, 'meeting_coordination', '0 30 8 * * MON-FRI', 'office1.meeting_rooms', 'meeting_coordination', '{"subject":{"platformPrincipalId":"digital-admin-duty-bot","platformPrincipalType":"service_account","subjectType":"service_account","subjectId":"office1.bot"},"window":"today","room_limit":10}', 'enabled'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'weekly_report_collection', '0 0 18 * * FRI', 'office1.work_types', 'weekly_report_collection', '{"subject":{"platformPrincipalId":"digital-admin-duty-bot","platformPrincipalType":"service_account","subjectType":"service_account","subjectId":"office1.bot"},"report_type":"weekly"}', 'enabled'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'approval_cleanup', '0 0 9 * * MON-FRI', 'office1.approval_flows', 'approval_cleanup', '{"subject":{"platformPrincipalId":"digital-admin-duty-bot","platformPrincipalType":"service_account","subjectType":"service_account","subjectId":"office1.bot"},"cate_id":1}', 'enabled'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1'
UNION ALL
SELECT rp.id, 'leave_duty_coordination', '0 30 17 * * MON-FRI', 'office1.my_leader', 'leave_duty_coordination', '{"subject":{"platformPrincipalId":"digital-admin-duty-bot","platformPrincipalType":"service_account","subjectType":"service_account","subjectId":"office1.bot"},"cate_id":1}', 'enabled'
FROM role_package rp
WHERE rp.agent_app_code = 'admin-agent' AND rp.role_code = 'digital-admin' AND rp.version = 'v1';
