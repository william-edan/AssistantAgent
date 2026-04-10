-- Keep V55 immutable for Flyway validation and patch the final default limit in a follow-up migration.

UPDATE tool_meta
SET parameter_schema = '{"type":"object","properties":{"query":{"type":"object","properties":{"page":{"type":"integer","default":1},"limit":{"type":"integer","default":100},"follow_time":{"type":"string"},"next_time":{"type":"string"},"industry_id":{"type":"string"},"grade_id":{"type":"string"},"source_id":{"type":"string"},"customer_status":{"type":"string"},"intent_status":{"type":"string","default":"8"},"username":{"type":"string"},"uid":{"type":"string"},"keywords":{"type":"string"},"tab":{"type":"string","default":"0"},"order_field":{"type":"string"},"order_type":{"type":"string"}},"required":["username"]}}}',
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND tool_code = 'gougu_oa.high_intent_customer_query';
