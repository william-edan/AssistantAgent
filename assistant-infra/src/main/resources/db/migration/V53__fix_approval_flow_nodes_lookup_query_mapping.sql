-- Fix approval-flow node lookup execution plan to pass query parameters via supported inputMapping shape.

UPDATE tool_meta
SET
    parameter_schema = '{"type":"object","properties":{"query":{"type":"object","properties":{"check_name":{"type":"string"},"action_id":{"type":"string"},"flow_id":{"type":"string"}},"required":["check_name"]}},"required":["query"]}',
    execution_plan = '{"version":"2.0","entry":["query"],"terminal":["query"],"steps":{"query":{"stepId":"query","name":"query_approval_flow_nodes","type":"HTTP","config":{"method":"GET","endpoint":"/api/check/get_flow_nodes","contentType":"application/json","inputMapping":{"query":"${query}"},"outputMapping":{"data":"$.data","message":"$.msg"},"successCondition":"$.code == 0"}}}}',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND tool_code = 'gougu_oa.approval_flow_nodes_lookup';
