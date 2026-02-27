CREATE TABLE token_lease (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    lease_id        VARCHAR(64) NOT NULL UNIQUE,
    access_token    TEXT NOT NULL,
    system_code     VARCHAR(64) NOT NULL,
    assistant_uid   VARCHAR(128) NOT NULL,
    expires_at      DATETIME NOT NULL,
    status          VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_lease_expires ON token_lease (expires_at);
CREATE INDEX idx_lease_uid ON token_lease (assistant_uid, system_code);
