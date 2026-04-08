-- Fix reward employee lookup tool_meta to use the OA integration employee directory endpoint.

UPDATE tool_meta
SET
    description = 'Query employee directory records for reward and punishment workflows.',
    api_endpoint = '/api/oa_integration/get_all_users',
    parameter_schema = '{"type":"object","properties":{}}',
    execution_plan = '{"version":"2.0","entry":["query"],"terminal":["query"],"steps":{"query":{"stepId":"query","name":"query_reward_employee_directory","type":"HTTP","config":{"method":"GET","endpoint":"/api/oa_integration/get_all_users","contentType":"application/json","outputMapping":{"data":"$.data","message":"$.msg"},"successCondition":"$.code == 0"}}}}',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND tool_code = 'gougu_oa.reward_employee_lookup';
