CREATE TABLE execution_run (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id              VARCHAR(128) NOT NULL,
    artifact_code       VARCHAR(128) NOT NULL,
    artifact_type       VARCHAR(32) NOT NULL,
    space_id            BIGINT NOT NULL,
    platform_principal_id VARCHAR(128),
    thread_id           VARCHAR(128),
    status              VARCHAR(32) NOT NULL,
    started_at          DATETIME,
    completed_at        DATETIME,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_execution_run_run_id (run_id)
);

CREATE INDEX idx_execution_run_space_status ON execution_run (space_id, status);
CREATE INDEX idx_execution_run_thread ON execution_run (thread_id);

CREATE TABLE execution_step (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    run_id              VARCHAR(128) NOT NULL,
    step_id             VARCHAR(128) NOT NULL,
    step_name           VARCHAR(256),
    connector_id        BIGINT,
    auth_profile_code   VARCHAR(128),
    principal_binding_id BIGINT,
    status              VARCHAR(32) NOT NULL,
    started_at          DATETIME,
    completed_at        DATETIME,
    error_message       TEXT,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_execution_step_run_step (run_id, step_id)
);

CREATE INDEX idx_execution_step_run_status ON execution_step (run_id, status);

CREATE TABLE approval_request (
    id                  BIGINT AUTO_INCREMENT PRIMARY KEY,
    request_id          VARCHAR(128) NOT NULL,
    run_id              VARCHAR(128) NOT NULL,
    step_id             VARCHAR(128) NOT NULL,
    approval_channel    VARCHAR(64),
    approver_principal_id VARCHAR(128),
    status              VARCHAR(32) NOT NULL,
    requested_at        DATETIME,
    responded_at        DATETIME,
    created_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_approval_request_request_id (request_id)
);

CREATE INDEX idx_approval_request_run_step_status ON approval_request (run_id, step_id, status);

ALTER TABLE audit_event
    ADD COLUMN run_id VARCHAR(128) NULL AFTER execution_id,
    ADD COLUMN step_id VARCHAR(128) NULL AFTER run_id,
    ADD COLUMN event_type VARCHAR(64) NULL AFTER step_id;

CREATE INDEX idx_audit_run ON audit_event (run_id);
CREATE INDEX idx_audit_run_step ON audit_event (run_id, step_id);
