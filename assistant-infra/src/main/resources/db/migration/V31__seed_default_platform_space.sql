-- 为迁移模式补齐默认企业空间，保证执行记录、审批读侧和任务中心具备稳定的空间主键。
INSERT INTO platform_space (space_code, space_name, environment, status)
SELECT 'default', '默认企业空间', 'prod', 'active'
WHERE NOT EXISTS (
    SELECT 1
    FROM platform_space
    WHERE space_code = 'default'
      AND environment = 'prod'
);
