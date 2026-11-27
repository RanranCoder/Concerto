CREATE TABLE pams_flyway.historical_import_state (
    singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
    snapshot_id varchar(128) NOT NULL,
    source_server_id varchar(64) NOT NULL,
    source_database varchar(64) NOT NULL,
    reconciliation_sha256 char(64) NOT NULL,
    imported_at timestamp with time zone NOT NULL DEFAULT clock_timestamp(),
    opened_at timestamp with time zone
);

-- A task may reference a later ID in the source snapshot. Normal application writes still check immediately.
ALTER TABLE pams.task ALTER CONSTRAINT fk_task_dep DEFERRABLE INITIALLY IMMEDIATE;
