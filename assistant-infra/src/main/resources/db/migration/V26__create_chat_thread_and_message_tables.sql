CREATE TABLE chat_thread (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    thread_id             VARCHAR(128) NOT NULL,
    assistant_uid         VARCHAR(128) NOT NULL,
    app_name              VARCHAR(128) NULL,
    system_code           VARCHAR(128) NULL,
    title                 VARCHAR(256) NULL,
    status                VARCHAR(32) NOT NULL DEFAULT 'UNDERSTANDING',
    phase                 VARCHAR(32) NOT NULL DEFAULT 'UNDERSTANDING',
    unfinished            TINYINT(1) NOT NULL DEFAULT 1,
    last_user_message     TEXT NULL,
    last_assistant_message TEXT NULL,
    last_message_preview  TEXT NULL,
    last_event_type       VARCHAR(64) NULL,
    last_message_at       DATETIME NULL,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chat_thread_thread_id (thread_id)
);

CREATE INDEX idx_chat_thread_assistant_updated ON chat_thread (assistant_uid, updated_at);
CREATE INDEX idx_chat_thread_assistant_status ON chat_thread (assistant_uid, status, updated_at);

CREATE TABLE chat_message (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id            VARCHAR(128) NOT NULL,
    thread_id             VARCHAR(128) NOT NULL,
    assistant_uid         VARCHAR(128) NOT NULL,
    turn_id               VARCHAR(128) NULL,
    source_key            VARCHAR(256) NULL,
    message_type          VARCHAR(32) NOT NULL,
    event_type            VARCHAR(64) NULL,
    stage                 VARCHAR(32) NULL,
    status                VARCHAR(32) NULL,
    title                 VARCHAR(256) NULL,
    summary_text          TEXT NULL,
    payload_json          LONGTEXT NOT NULL,
    collapsed             TINYINT(1) NOT NULL DEFAULT 0,
    revision_no           INT NOT NULL DEFAULT 1,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_chat_message_message_id (message_id),
    UNIQUE KEY uk_chat_message_source_key (source_key)
);

CREATE INDEX idx_chat_message_thread_created ON chat_message (thread_id, created_at, id);
CREATE INDEX idx_chat_message_assistant_created ON chat_message (assistant_uid, created_at, id);
