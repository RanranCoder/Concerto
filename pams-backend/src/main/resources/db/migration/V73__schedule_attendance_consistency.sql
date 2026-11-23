ALTER TABLE signin ADD COLUMN schedule_id BIGINT;
CREATE INDEX idx_signin_org_schedule ON signin (org_id, schedule_id);

ALTER TABLE activity ADD COLUMN checkin_token VARCHAR(64);
ALTER TABLE activity ADD COLUMN checkin_expires_at DATETIME;
CREATE UNIQUE INDEX uq_activity_checkin_token ON activity (checkin_token);

ALTER TABLE schedule ADD COLUMN generation_dept_ids TEXT;
ALTER TABLE schedule ADD COLUMN generation_people_limit INT;
ALTER TABLE schedule ADD COLUMN generation_semester_start DATE;

CREATE TABLE course_schedule_submission (
    org_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    semester VARCHAR(20) NOT NULL,
    submitted_at DATETIME NOT NULL,
    PRIMARY KEY (org_id, user_id, semester)
);

ALTER TABLE attendance MODIFY COLUMN remark VARCHAR(255);
