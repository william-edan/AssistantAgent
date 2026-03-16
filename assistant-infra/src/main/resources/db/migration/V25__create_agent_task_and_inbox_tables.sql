CREATE TABLE agent_task (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_id           VARCHAR(128) NOT NULL,
    thread_id         VARCHAR(128) NULL,
    assistant_uid     VARCHAR(128) NULL,
    run_id            VARCHAR(128) NULL,
    task_type         VARCHAR(64) NOT NULL,
    source_type       VARCHAR(64) NULL,
    source_code       VARCHAR(128) NULL,
    title             VARCHAR(256) NOT NULL,
    status            VARCHAR(32) NOT NULL,
    progress_percent  INT NULL,
    collapsible       TINYINT(1) NOT NULL DEFAULT 1,
    result_ready      TINYINT(1) NOT NULL DEFAULT 0,
    latest_output_json LONGTEXT NULL,
    result_preview_json LONGTEXT NULL,
    action_json       LONGTEXT NULL,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at      DATETIME NULL,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_task_task_id (task_id)
);

CREATE INDEX idx_agent_task_assistant_status ON agent_task (assistant_uid, status);
CREATE INDEX idx_agent_task_thread_updated ON agent_task (thread_id, updated_at);
CREATE INDEX idx_agent_task_run ON agent_task (run_id);

CREATE TABLE agent_task_event (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id          VARCHAR(128) NOT NULL,
    task_id           VARCHAR(128) NOT NULL,
    thread_id         VARCHAR(128) NULL,
    assistant_uid     VARCHAR(128) NULL,
    event_type        VARCHAR(64) NOT NULL,
    status            VARCHAR(32) NOT NULL,
    sequence_no       BIGINT NULL,
    payload_json      LONGTEXT NULL,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_agent_task_event_event_id (event_id)
);

CREATE INDEX idx_agent_task_event_task_sequence ON agent_task_event (task_id, sequence_no);
CREATE INDEX idx_agent_task_event_assistant_created ON agent_task_event (assistant_uid, created_at);

CREATE TABLE user_inbox_notification (
    id                BIGINT AUTO_INCREMENT PRIMARY KEY,
    notification_id   VARCHAR(128) NOT NULL,
    assistant_uid     VARCHAR(128) NOT NULL,
    task_id           VARCHAR(128) NULL,
    thread_id         VARCHAR(128) NULL,
    status            VARCHAR(32) NOT NULL,
    title             VARCHAR(256) NOT NULL,
    body              TEXT NULL,
    action_json       LONGTEXT NULL,
    read_at           DATETIME NULL,
    created_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at        DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_user_inbox_notification_notification_id (notification_id)
);

CREATE INDEX idx_user_inbox_notification_assistant_status ON user_inbox_notification (assistant_uid, status, created_at);
CREATE INDEX idx_user_inbox_notification_thread ON user_inbox_notification (thread_id, created_at);
CREATE INDEX idx_user_inbox_notification_task ON user_inbox_notification (task_id);
