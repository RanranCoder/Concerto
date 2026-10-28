-- Phase-one lifecycle, review and audit metadata. Keep V47 records and legacy revisions intact.
ALTER TABLE sys_role ADD COLUMN updated_by BIGINT;
ALTER TABLE role_revision ADD COLUMN catalog_version INTEGER;
ALTER TABLE role_revision ADD COLUMN publish_reason VARCHAR(500);
ALTER TABLE role_revision ADD COLUMN published_by_name VARCHAR(100);
ALTER TABLE org_role_delegation ADD COLUMN updated_at TIMESTAMP;
ALTER TABLE org_role_delegation ADD COLUMN updated_by BIGINT;
ALTER TABLE org_audit_log ADD COLUMN outcome VARCHAR(20) NOT NULL DEFAULT 'APPLIED';
ALTER TABLE org_audit_log ADD COLUMN reason VARCHAR(500);
ALTER TABLE org_audit_log ADD COLUMN target_type VARCHAR(30);
ALTER TABLE org_audit_log ADD COLUMN change_id VARCHAR(64);
CREATE INDEX idx_audit_change ON org_audit_log(org_id, change_id);
UPDATE schedule SET status='PUBLISHED' WHERE status IS NULL;
