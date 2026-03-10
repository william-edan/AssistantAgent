CREATE TABLE local_user_grant (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    local_user_id BIGINT NOT NULL,
    grant_type VARCHAR(32) NOT NULL,
    grant_code VARCHAR(128) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_local_user_grant (local_user_id, grant_type, grant_code),
    KEY idx_local_user_grant_lookup (local_user_id, grant_type, status)
);

INSERT INTO local_user_grant (local_user_id, grant_type, grant_code, status)
SELECT id, 'role', 'assistant_user', 'active'
FROM local_user_account
WHERE username = 'admin' AND system_code = 'gougu_oa'
ON DUPLICATE KEY UPDATE
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO local_user_grant (local_user_id, grant_type, grant_code, status)
SELECT id, 'role', 'assistant_controlplane_admin', 'active'
FROM local_user_account
WHERE username = 'admin' AND system_code = 'gougu_oa'
ON DUPLICATE KEY UPDATE
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO local_user_grant (local_user_id, grant_type, grant_code, status)
SELECT id, 'permission', 'assistant:chat', 'active'
FROM local_user_account
WHERE username = 'admin' AND system_code = 'gougu_oa'
ON DUPLICATE KEY UPDATE
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;

INSERT INTO local_user_grant (local_user_id, grant_type, grant_code, status)
SELECT id, 'permission', 'assistant:controlplane', 'active'
FROM local_user_account
WHERE username = 'admin' AND system_code = 'gougu_oa'
ON DUPLICATE KEY UPDATE
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;
