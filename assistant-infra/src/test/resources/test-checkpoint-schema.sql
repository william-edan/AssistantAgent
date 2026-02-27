CREATE TABLE checkpoint (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    thread_id       VARCHAR(128) NOT NULL,
    checkpoint_id   VARCHAR(128) NOT NULL,
    state_json      CLOB NOT NULL,
    parent_id       VARCHAR(128),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE (thread_id, checkpoint_id)
);

CREATE INDEX idx_checkpoint_thread ON checkpoint (thread_id, created_at DESC);
