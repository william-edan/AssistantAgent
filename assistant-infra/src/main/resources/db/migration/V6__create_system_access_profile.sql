CREATE TABLE system_access_profile (
    id                   BIGINT AUTO_INCREMENT PRIMARY KEY,
    system_code          VARCHAR(64) NOT NULL UNIQUE,
    base_url             VARCHAR(512) NOT NULL,
    token_endpoint       VARCHAR(512),
    token_method         VARCHAR(16) DEFAULT 'POST',
    token_request_tpl    TEXT,
    token_response_path  VARCHAR(256) DEFAULT 'data.token',
    token_header_name    VARCHAR(64) DEFAULT 'Authorization',
    token_header_prefix  VARCHAR(64) DEFAULT 'Bearer ',
    token_ttl_seconds    INT DEFAULT 7200,
    status               VARCHAR(16) NOT NULL DEFAULT 'enabled',
    created_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at           DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
);
