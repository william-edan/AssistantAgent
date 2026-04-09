-- Fix the car-fee vehicle lookup endpoint for existing migration environments.

UPDATE tool_meta
SET api_endpoint = '/adm/api/get_car?page=1&limit=10',
    execution_plan = '{"version":"2.0","entry":["query"],"terminal":["query"],"steps":{"query":{"stepId":"query","name":"query_car_list","type":"HTTP","config":{"method":"GET","endpoint":"/adm/api/get_car?page=1&limit=10","contentType":"application/json","outputMapping":{"data":"$.data","message":"$.msg"},"successCondition":"$.code == 0"}}}}',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND tool_code = 'gougu_oa.car_fee_car_lookup';
