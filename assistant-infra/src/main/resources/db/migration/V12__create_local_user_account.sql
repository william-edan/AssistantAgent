CREATE TABLE local_user_account (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    username        VARCHAR(128) NOT NULL,
    password_hash   VARCHAR(128) NOT NULL,
    display_name    VARCHAR(128),
    tenant_id       BIGINT,
    system_code     VARCHAR(64) NOT NULL DEFAULT 'gougu_oa',
    status          VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_local_user_system (username, system_code),
    KEY idx_local_user_tenant (tenant_id)
);

INSERT INTO local_user_account (username, password_hash, display_name, tenant_id, system_code, status)
VALUES ('admin', SHA2('admin123', 256), '管理员', 1, 'gougu_oa', 'active')
ON DUPLICATE KEY UPDATE
    password_hash = VALUES(password_hash),
    display_name = VALUES(display_name),
    tenant_id = VALUES(tenant_id),
    status = VALUES(status),
    updated_at = CURRENT_TIMESTAMP;
