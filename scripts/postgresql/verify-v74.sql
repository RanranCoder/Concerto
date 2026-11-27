-- Run as the runtime role against a disposable acceptance database after migration.
\set ON_ERROR_STOP on
BEGIN;
SET LOCAL search_path TO pams;
SET LOCAL client_encoding TO 'UTF8';

DO $$
BEGIN
    IF has_schema_privilege('pams', 'CREATE')
        OR has_schema_privilege('pams_flyway', 'CREATE')
        OR has_table_privilege('pams_flyway.flyway_schema_history', 'INSERT, UPDATE, DELETE, TRUNCATE') THEN
        RAISE EXCEPTION 'Runtime role must not have schema or history write privileges';
    END IF;

    INSERT INTO sys_role (id, org_id, code, name) VALUES (-1, -1, 'VERIFY', 'Verification only');
    INSERT INTO sys_user (id, org_id, username, password, real_name, role_id)
        VALUES (-1, -1, 'VérifyV74', 'disabled', 'Verification only', -1);
    INSERT INTO sys_user (id, org_id, username, password, real_name, role_id)
        VALUES (-2, -2, 'verifyv74', 'disabled', 'Verification only', -1) ON CONFLICT DO NOTHING;
    IF FOUND THEN
        RAISE EXCEPTION 'Global username uniqueness must ignore case and accents across organizations';
    END IF;

    BEGIN
        INSERT INTO sys_user (id, username, password, real_name, role_id)
            VALUES (-3, 'v74-invalid-role', 'disabled', 'Verification only', -999);
        RAISE EXCEPTION 'Missing role must violate the user foreign key';
    EXCEPTION WHEN foreign_key_violation THEN
        NULL;
    END;

    INSERT INTO member_session (id, org_id, name, deleted) VALUES
        (-1, -1, 'Verify session', 0),
        (-2, -2, 'Verify session', 0),
        (-3, -1, 'Verify session', -3);
    INSERT INTO member_session (id, org_id, name, deleted)
        VALUES (-4, -1, 'verify session', 0) ON CONFLICT DO NOTHING;
    IF FOUND THEN
        RAISE EXCEPTION 'Active member session names must be unique within an organization';
    END IF;

    INSERT INTO no_class_schedule (id, org_id, dept_id, semester, grid_json) VALUES
        (-1, -1, -1, 'Verify', '{}'),
        (-2, -2, -1, 'Verify', '{}');
    INSERT INTO no_class_schedule (id, org_id, dept_id, semester, grid_json)
        VALUES (-3, -1, -1, 'Verify', '{}') ON CONFLICT DO NOTHING;
    IF FOUND THEN
        RAISE EXCEPTION 'Schedule publication uniqueness must include organization, department and semester';
    END IF;

    INSERT INTO notification_inbox (id, org_id, message_id, user_id, created_at)
        VALUES (-1, -1, -1, -1, CURRENT_TIMESTAMP);
    INSERT INTO notification_inbox (id, org_id, message_id, user_id, created_at)
        VALUES (-2, -1, -1, -1, CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING;
    IF FOUND THEN
        RAISE EXCEPTION 'Inbox must deduplicate by organization, message and user';
    END IF;
END
$$;

ROLLBACK;
