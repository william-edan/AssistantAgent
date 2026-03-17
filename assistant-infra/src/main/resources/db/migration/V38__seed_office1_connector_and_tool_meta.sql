-- Seed Office1 connector, auth profiles, bindings and canonical tool meta for migration profile.

INSERT INTO connector (
    space_id,
    connector_code,
    system_code,
    display_name,
    protocol_type,
    environment,
    network_zone,
    base_url,
    status,
    version
)
SELECT
    ps.id,
    'office1-adapter',
    'office1',
    'Office1 适配器',
    'HTTP',
    'prod',
    'intranet',
    'http://office.ai.devefive.com',
    'active',
    1
FROM platform_space ps
WHERE ps.space_code = 'default'
  AND ps.environment = 'prod'
ON DUPLICATE KEY UPDATE
    system_code = VALUES(system_code),
    display_name = VALUES(display_name),
    protocol_type = VALUES(protocol_type),
    environment = VALUES(environment),
    network_zone = VALUES(network_zone),
    base_url = VALUES(base_url),
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO auth_profile (
    space_id,
    connector_id,
    auth_profile_code,
    auth_type,
    usage_policy,
    token_endpoint,
    token_header_name,
    token_header_prefix,
    audience,
    scopes_json,
    credential_ref,
    refresh_policy_json,
    status
)
SELECT
    c.space_id,
    c.id,
    'office1-delegated',
    'token_exchange',
    'delegated',
    '/api/oa_integration/get_token',
    'Authorization',
    'Bearer ',
    'office1',
    JSON_ARRAY('oa.read', 'oa.write'),
    NULL,
    JSON_OBJECT('renewBeforeSeconds', 300),
    'active'
FROM connector c
WHERE c.connector_code = 'office1-adapter'
  AND c.environment = 'prod'
ON DUPLICATE KEY UPDATE
    auth_type = VALUES(auth_type),
    usage_policy = VALUES(usage_policy),
    token_endpoint = VALUES(token_endpoint),
    token_header_name = VALUES(token_header_name),
    token_header_prefix = VALUES(token_header_prefix),
    audience = VALUES(audience),
    scopes_json = VALUES(scopes_json),
    credential_ref = VALUES(credential_ref),
    refresh_policy_json = VALUES(refresh_policy_json),
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO auth_profile (
    space_id,
    connector_id,
    auth_profile_code,
    auth_type,
    usage_policy,
    token_endpoint,
    token_header_name,
    token_header_prefix,
    audience,
    scopes_json,
    credential_ref,
    refresh_policy_json,
    status
)
SELECT
    c.space_id,
    c.id,
    'office1-service',
    'bearer',
    'service_account',
    NULL,
    'Authorization',
    'Bearer ',
    'office1',
    JSON_ARRAY('oa.read', 'oa.write'),
    'office1-service-bearer-token',
    JSON_OBJECT('mode', 'static'),
    'active'
FROM connector c
WHERE c.connector_code = 'office1-adapter'
  AND c.environment = 'prod'
ON DUPLICATE KEY UPDATE
    auth_type = VALUES(auth_type),
    usage_policy = VALUES(usage_policy),
    token_header_name = VALUES(token_header_name),
    token_header_prefix = VALUES(token_header_prefix),
    audience = VALUES(audience),
    scopes_json = VALUES(scopes_json),
    credential_ref = VALUES(credential_ref),
    refresh_policy_json = VALUES(refresh_policy_json),
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO identity_binding (assistant_uid, system_code, system_user_id, auth_type, credentials, status)
VALUES
    ('1', 'office1', '1', 'TOKEN', 'office1-user-delegated-token', 'active'),
    ('office1.bot', 'office1', 'office1.bot', 'TOKEN', 'office1-service-bearer-token', 'active')
ON DUPLICATE KEY UPDATE
    system_user_id = VALUES(system_user_id),
    auth_type = VALUES(auth_type),
    credentials = VALUES(credentials),
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO principal_binding (
    space_id,
    connector_id,
    platform_principal_id,
    platform_principal_type,
    target_principal_type,
    target_principal_id,
    scope_constraints_json,
    priority,
    status
)
SELECT
    c.space_id,
    c.id,
    '1',
    'local_user',
    'delegated',
    '1',
    JSON_OBJECT('connectorCode', 'office1-adapter', 'usagePolicy', 'delegated'),
    10,
    'active'
FROM connector c
WHERE c.connector_code = 'office1-adapter'
  AND c.environment = 'prod'
ON DUPLICATE KEY UPDATE
    platform_principal_type = VALUES(platform_principal_type),
    target_principal_type = VALUES(target_principal_type),
    target_principal_id = VALUES(target_principal_id),
    scope_constraints_json = VALUES(scope_constraints_json),
    priority = VALUES(priority),
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO principal_binding (
    space_id,
    connector_id,
    platform_principal_id,
    platform_principal_type,
    target_principal_type,
    target_principal_id,
    scope_constraints_json,
    priority,
    status
)
SELECT
    c.space_id,
    c.id,
    'digital-admin-duty-bot',
    'service_account',
    'service_account',
    'office1.bot',
    JSON_OBJECT('connectorCode', 'office1-adapter', 'usagePolicy', 'service_account', 'rolePackageCode', 'digital-admin'),
    5,
    'active'
FROM connector c
WHERE c.connector_code = 'office1-adapter'
  AND c.environment = 'prod'
ON DUPLICATE KEY UPDATE
    platform_principal_type = VALUES(platform_principal_type),
    target_principal_type = VALUES(target_principal_type),
    target_principal_id = VALUES(target_principal_id),
    scope_constraints_json = VALUES(scope_constraints_json),
    priority = VALUES(priority),
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;

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
) VALUES
(
    'default',
    'office1.meeting_rooms',
    '会议室列表',
    '查询 Office1 可用会议室列表。',
    'office1',
    '/api/oa_integration/get_meeting_rooms',
    'GET',
    'application/json',
    '{"slots":[]}',
    '{"version":"2.0","mode":"SYNC","entry":["query"],"terminal":["query"],"steps":{"query":{"stepId":"query","name":"query","type":"HTTP","config":{"method":"GET","endpoint":"/api/oa_integration/get_meeting_rooms","contentType":"application/json","successCondition":"$.code == 0","outputMapping":{"data":"$.data","message":"$.msg"}}}}}',
    '{"toolType":"QUERY","visibility":"INTERNAL","invocationPolicy":"DEPENDENCY_ONLY","executionMode":"SYNC","keywords":["会议室","会议安排","会议协调"]}',
    'LOW',
    1,
    0,
    'READ',
    1,
    'enabled'
),
(
    'default',
    'office1.meeting_requirements',
    '会议需求选项',
    '查询 Office1 会议需求选项。',
    'office1',
    '/api/oa_integration/get_meeting_requirements',
    'GET',
    'application/json',
    '{"slots":[]}',
    '{"version":"2.0","mode":"SYNC","entry":["query"],"terminal":["query"],"steps":{"query":{"stepId":"query","name":"query","type":"HTTP","config":{"method":"GET","endpoint":"/api/oa_integration/get_meeting_requirements","contentType":"application/json","successCondition":"$.code == 0","outputMapping":{"data":"$.data","message":"$.msg"}}}}}',
    '{"toolType":"QUERY","visibility":"INTERNAL","invocationPolicy":"DEPENDENCY_ONLY","executionMode":"SYNC","keywords":["会议需求","投影仪","白板"]}',
    'LOW',
    1,
    0,
    'READ',
    1,
    'enabled'
),
(
    'default',
    'office1.work_types',
    '工作汇报类型',
    '查询日报、周报、月报类型。',
    'office1',
    '/api/oa_integration/get_work_types',
    'GET',
    'application/json',
    '{"slots":[]}',
    '{"version":"2.0","mode":"SYNC","entry":["query"],"terminal":["query"],"steps":{"query":{"stepId":"query","name":"query","type":"HTTP","config":{"method":"GET","endpoint":"/api/oa_integration/get_work_types","contentType":"application/json","successCondition":"$.code == 0","outputMapping":{"data":"$.data","message":"$.msg"}}}}}',
    '{"toolType":"QUERY","visibility":"INTERNAL","invocationPolicy":"DEPENDENCY_ONLY","executionMode":"SYNC","keywords":["周报","日报","月报"]}',
    'LOW',
    1,
    0,
    'READ',
    1,
    'enabled'
),
(
    'default',
    'office1.leave_types',
    '请假类型',
    '查询 Office1 请假类型。',
    'office1',
    '/api/oa_integration/get_leave_types',
    'GET',
    'application/json',
    '{"slots":[]}',
    '{"version":"2.0","mode":"SYNC","entry":["query"],"terminal":["query"],"steps":{"query":{"stepId":"query","name":"query","type":"HTTP","config":{"method":"GET","endpoint":"/api/oa_integration/get_leave_types","contentType":"application/json","successCondition":"$.code == 0","outputMapping":{"data":"$.data","message":"$.msg"}}}}}',
    '{"toolType":"QUERY","visibility":"INTERNAL","invocationPolicy":"DEPENDENCY_ONLY","executionMode":"SYNC","keywords":["请假","休假","值班"]}',
    'LOW',
    1,
    0,
    'READ',
    1,
    'enabled'
),
(
    'default',
    'office1.approval_flows',
    '审批流程列表',
    '根据审批类型查询 Office1 审批流程。',
    'office1',
    '/api/oa_integration/get_approval_flows',
    'GET',
    'application/json',
    '{"slots":[{"name":"cate_id","type":"integer","title":"审批分类","required":false,"defaultValue":1}]}',
    '{"version":"2.0","mode":"SYNC","entry":["query"],"terminal":["query"],"steps":{"query":{"stepId":"query","name":"query","type":"HTTP","config":{"method":"GET","endpoint":"/api/oa_integration/get_approval_flows","contentType":"application/json","queryMappings":{"cate_id":"${cate_id}"},"successCondition":"$.code == 0","outputMapping":{"data":"$.data","message":"$.msg"}}}}}',
    '{"toolType":"QUERY","visibility":"INTERNAL","invocationPolicy":"DEPENDENCY_ONLY","executionMode":"SYNC","keywords":["审批流程","审批清理","流程匹配"]}',
    'LOW',
    1,
    0,
    'READ',
    1,
    'enabled'
),
(
    'default',
    'office1.employees',
    '员工目录',
    '按关键字查询 Office1 员工目录。',
    'office1',
    '/api/oa_integration/get_employees',
    'GET',
    'application/json',
    '{"slots":[{"name":"keywords","type":"string","title":"关键字","required":false}]}',
    '{"version":"2.0","mode":"SYNC","entry":["query"],"terminal":["query"],"steps":{"query":{"stepId":"query","name":"query","type":"HTTP","config":{"method":"GET","endpoint":"/api/oa_integration/get_employees","contentType":"application/json","queryMappings":{"keywords":"${keywords}"},"successCondition":"$.code == 0","outputMapping":{"data":"$.data","message":"$.msg"}}}}}',
    '{"toolType":"QUERY","visibility":"INTERNAL","invocationPolicy":"DEPENDENCY_ONLY","executionMode":"SYNC","keywords":["员工","通讯录","收件人"]}',
    'LOW',
    1,
    0,
    'READ',
    1,
    'enabled'
),
(
    'default',
    'office1.my_leader',
    '直属领导',
    '查询当前用户在 Office1 的直属领导。',
    'office1',
    '/api/oa_integration/get_my_leader',
    'GET',
    'application/json',
    '{"slots":[]}',
    '{"version":"2.0","mode":"SYNC","entry":["query"],"terminal":["query"],"steps":{"query":{"stepId":"query","name":"query","type":"HTTP","config":{"method":"GET","endpoint":"/api/oa_integration/get_my_leader","contentType":"application/json","successCondition":"$.code == 0","outputMapping":{"data":"$.data","message":"$.msg"}}}}}',
    '{"toolType":"QUERY","visibility":"INTERNAL","invocationPolicy":"DEPENDENCY_ONLY","executionMode":"SYNC","keywords":["直属领导","审批人","值班协调"]}',
    'LOW',
    1,
    0,
    'READ',
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
