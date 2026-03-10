CREATE TABLE platform_space (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    space_code VARCHAR(64) NOT NULL,
    space_name VARCHAR(128) NOT NULL,
    environment VARCHAR(16) NOT NULL DEFAULT 'prod',
    status VARCHAR(16) NOT NULL DEFAULT 'active',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_space_code_env (space_code, environment)
);
