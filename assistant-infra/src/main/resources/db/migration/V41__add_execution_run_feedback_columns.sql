ALTER TABLE execution_run
    ADD COLUMN user_rating INT NULL AFTER approval_request_id,
    ADD COLUMN correction_note TEXT NULL AFTER user_rating;
