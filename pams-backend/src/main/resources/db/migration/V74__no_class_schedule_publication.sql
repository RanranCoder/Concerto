ALTER TABLE no_class_schedule MODIFY COLUMN grid_json MEDIUMTEXT NOT NULL;
ALTER TABLE no_class_schedule MODIFY COLUMN created_at DATETIME(6);
ALTER TABLE no_class_schedule ADD CONSTRAINT uk_no_class_org_dept_semester UNIQUE (org_id, dept_id, semester);
ALTER TABLE no_class_schedule DROP INDEX uk_no_class_dept_semester;
