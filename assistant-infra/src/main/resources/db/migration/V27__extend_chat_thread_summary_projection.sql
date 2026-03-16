ALTER TABLE chat_thread
    ADD COLUMN can_resume TINYINT(1) NOT NULL DEFAULT 0 AFTER unfinished,
    ADD COLUMN tool_code VARCHAR(256) NULL AFTER can_resume,
    ADD COLUMN pending_card_type VARCHAR(32) NULL AFTER tool_code;

CREATE INDEX idx_chat_thread_assistant_resume ON chat_thread (assistant_uid, can_resume, updated_at);
