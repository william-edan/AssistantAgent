-- 初始化默认测试绑定与本地管理员绑定。
-- 这样新库首次启动后即可直接完成真实登录与令牌交换测试。

INSERT INTO identity_binding (assistant_uid, system_code, system_user_id, auth_type, credentials, status)
VALUES
    ('test_user', 'gougu_oa', '2', 'TOKEN', NULL, 'active'),
    ('1', 'gougu_oa', '2', 'TOKEN', NULL, 'active')
ON DUPLICATE KEY UPDATE
    system_user_id = VALUES(system_user_id),
    auth_type = VALUES(auth_type),
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;
