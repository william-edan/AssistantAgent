ALTER TABLE local_user_grant
    ADD COLUMN scope_type VARCHAR(32) NOT NULL DEFAULT 'global' AFTER grant_code,
    ADD COLUMN scope_code VARCHAR(255) NOT NULL DEFAULT '*' AFTER scope_type;

ALTER TABLE local_user_grant
    DROP INDEX uk_local_user_grant,
    ADD UNIQUE KEY uk_local_user_grant (local_user_id, grant_type, grant_code, scope_type, scope_code);

ALTER TABLE local_user_grant
    DROP INDEX idx_local_user_grant_lookup,
    ADD KEY idx_local_user_grant_lookup (local_user_id, grant_type, status, scope_type, scope_code, grant_code);
