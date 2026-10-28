ALTER TABLE task ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
-- Keep unscheduled tasks visible without inventing calendar dates.
ALTER TABLE task MODIFY COLUMN start_date DATE NULL;
ALTER TABLE task MODIFY COLUMN end_date DATE NULL;
