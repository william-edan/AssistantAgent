CREATE TABLE identity_binding (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    assistant_uid   VARCHAR(128) NOT NULL,
    system_code     VARCHAR(64) NOT NULL,
    system_user_id  VARCHAR(128) NOT NULL,
    auth_type       VARCHAR(32) NOT NULL DEFAULT 'TOKEN',
    credentials     TEXT,
    status          VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_uid_system (assistant_uid, system_code)
);
