CREATE TABLE proactive_run_lease (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_key     VARCHAR(256) NOT NULL,
    dedup_key    VARCHAR(320) NOT NULL,
    scheduled_at DATETIME NOT NULL,
    lease_owner  VARCHAR(128) NULL,
    lease_until  DATETIME NULL,
    run_status   VARCHAR(32) NOT NULL DEFAULT 'PENDING',
    run_id       VARCHAR(128) NULL,
    last_error   TEXT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_proactive_run_lease_task_schedule (task_key, scheduled_at),
    UNIQUE KEY uk_proactive_run_lease_dedup (dedup_key)
);

CREATE INDEX idx_proactive_run_lease_status_schedule ON proactive_run_lease (run_status, scheduled_at);
CREATE INDEX idx_proactive_run_lease_owner_until ON proactive_run_lease (lease_owner, lease_until);
