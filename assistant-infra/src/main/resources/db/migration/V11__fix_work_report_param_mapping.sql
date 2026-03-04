-- Align work_report payload with Gougu OA /oa/work/add expectations.
-- 1) to_uids should be string-like (comma separated ids), not array-only semantics.
-- 2) include end_date in execution mapping for stricter PHP-side date handling.

UPDATE assistant_capability_registry
SET
    slot_schema = REPLACE(
            slot_schema,
            '"name": "to_uids", "type": "array"',
            '"name": "to_uids", "type": "string"'
                  ),
    flow_steps = REPLACE(
            flow_steps,
            '"range_date": "${range_date}", "start_date": "${start_date}"',
            '"range_date": "${range_date}", "start_date": "${start_date}", "end_date": "${end_date}"'
                 ),
    updated_at = CURRENT_TIMESTAMP
WHERE system_code = 'gougu_oa'
  AND capability_code = 'work_report'
  AND tenant_id = 'default';

UPDATE tool_meta
SET
    parameter_schema = REPLACE(
            parameter_schema,
            '"name": "to_uids", "type": "array"',
            '"name": "to_uids", "type": "string"'
                       ),
    execution_plan = REPLACE(
            execution_plan,
            '"range_date": "${range_date}", "start_date": "${start_date}"',
            '"range_date": "${range_date}", "start_date": "${start_date}", "end_date": "${end_date}"'
                     ),
    updated_at = CURRENT_TIMESTAMP
WHERE tenant_id = 'default'
  AND tool_code = 'gougu_oa.work_report'
  AND version = 1;
