CREATE TABLE audit_event (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    event_id        VARCHAR(64) NOT NULL UNIQUE,
    trace_id        VARCHAR(64) NOT NULL,
    execution_id    VARCHAR(64),
    thread_id       VARCHAR(64),
    assistant_uid   VARCHAR(128),
    system_code     VARCHAR(64),
    tool_name       VARCHAR(128),
    agent_phase     VARCHAR(16),
    tool_input      TEXT,
    tool_output     TEXT,
    duration_ms     BIGINT,
    status          VARCHAR(16) NOT NULL,
    error_message   TEXT,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_audit_trace ON audit_event (trace_id);
CREATE INDEX idx_audit_thread ON audit_event (thread_id);
CREATE INDEX idx_audit_created ON audit_event (created_at);
