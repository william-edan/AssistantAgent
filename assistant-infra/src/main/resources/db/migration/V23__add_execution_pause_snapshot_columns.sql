ALTER TABLE execution_run
    ADD COLUMN context_snapshot_json JSON NULL AFTER thread_id,
    ADD COLUMN paused_step_id VARCHAR(128) NULL AFTER status,
    ADD COLUMN approval_request_id VARCHAR(128) NULL AFTER paused_step_id;

CREATE INDEX idx_execution_run_approval_request ON execution_run (approval_request_id);
