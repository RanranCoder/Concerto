ALTER TABLE activity_plan ADD COLUMN document_file_id BIGINT NULL;
ALTER TABLE activity_plan ADD COLUMN document_text LONGTEXT NULL;
CREATE INDEX idx_plan_document_file ON activity_plan(document_file_id);
