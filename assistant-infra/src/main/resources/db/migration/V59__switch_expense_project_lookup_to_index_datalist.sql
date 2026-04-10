-- Switch expense project lookup to /project/index/datalist with extended query filters.

UPDATE tool_meta
SET
    api_endpoint = '/project/index/datalist',
    parameter_schema = '{"type":"object","properties":{"query":{"type":"object","properties":{"page":{"type":"string"},"limit":{"type":"string"},"status":{"type":"string"},"cate_id":{"type":"string"},"director":{"type":"string"},"director_uid":{"type":"string"},"keywords":{"type":"string"}}}}',
    execution_plan = '{"version":"2.0","entry":["query"],"terminal":["query"],"steps":{"query":{"stepId":"query","name":"query_expense_project_options","type":"HTTP","config":{"method":"GET","endpoint":"/project/index/datalist","contentType":"application/json","inputMapping":{"query":"${query}"},"outputMapping":{"data":"$.data","message":"$.msg"},"successCondition":"$.code == 0"}}}}',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND tool_code = 'gougu_oa.expense_project_lookup';
