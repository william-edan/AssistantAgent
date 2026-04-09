-- Extend seal submit-check tool_meta payload to align with OA submit_check expectations.

UPDATE tool_meta
SET
    parameter_schema = '{"type":"object","properties":{"action_id":{"type":"string"},"check_name":{"type":"string"},"flow_id":{"type":"string"},"check_uids":{"type":"string"},"check_copy_uids":{"type":"string"}},"required":["action_id","check_name"]}',
    execution_plan = '{"version":"2.0","entry":["submit"],"terminal":["submit"],"steps":{"submit":{"stepId":"submit","name":"submit_seal_apply_approval","type":"HTTP","config":{"method":"POST","endpoint":"/api/check/submit_check","contentType":"application/x-www-form-urlencoded","inputMapping":{"action_id":"${action_id}","check_name":"${check_name}","flow_id":"${flow_id}","check_uids":"${check_uids}","check_copy_uids":"${check_copy_uids}"},"outputMapping":{"data":"$.data","message":"$.msg"},"successCondition":"$.code == 0"}}}}',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND tool_code = 'gougu_oa.seal_apply_submit';
