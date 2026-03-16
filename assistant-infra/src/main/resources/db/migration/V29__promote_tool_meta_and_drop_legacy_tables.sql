-- 清理已经退役的旧定义表，保证库结构只保留 canonical 模型。
DROP TABLE IF EXISTS workflow_step;
DROP TABLE IF EXISTS workflow_spec;
DROP TABLE IF EXISTS interaction_spec;
DROP TABLE IF EXISTS action_spec;
DROP TABLE IF EXISTS precondition_check;
DROP TABLE IF EXISTS business_query_action;
DROP TABLE IF EXISTS reference_resolver;
DROP TABLE IF EXISTS assistant_capability_registry;
DROP TABLE IF EXISTS assistant_system_registry;
