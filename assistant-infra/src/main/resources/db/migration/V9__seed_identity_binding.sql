-- Seed identity_binding for default test user.
-- Enables real API calls to gougu_oa system via token exchange.
-- credentials = NULL means the token will be obtained via system_access_profile token exchange.

INSERT INTO identity_binding (assistant_uid, system_code, system_user_id, auth_type, credentials, status)
VALUES ('test_user', 'gougu_oa', '2', 'TOKEN', NULL, 'active')
ON DUPLICATE KEY UPDATE system_user_id = VALUES(system_user_id), status = VALUES(status);
