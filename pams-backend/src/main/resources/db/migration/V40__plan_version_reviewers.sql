ALTER TABLE activity_plan ADD COLUMN owner_id BIGINT;
ALTER TABLE activity_plan ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
UPDATE activity_plan p SET owner_id = COALESCE(submitter_id, (SELECT a.created_by FROM activity a WHERE a.id=p.activity_id));
-- Legacy versions did not capture document metadata; freeze the best available values once.
UPDATE activity_plan p SET
 name_override=COALESCE(name_override,(SELECT a.name FROM activity a WHERE a.id=p.activity_id),''),
 theme_override=COALESCE(theme_override,(SELECT a.theme FROM activity a WHERE a.id=p.activity_id),''),
 location_override=COALESCE(location_override,(SELECT a.location FROM activity a WHERE a.id=p.activity_id),''),
 organizer_override=COALESCE(organizer_override,(SELECT a.organizer FROM activity a WHERE a.id=p.activity_id),''),
 target_override=COALESCE(target_override,(SELECT a.target_audience FROM activity a WHERE a.id=p.activity_id),''),
 time_override=COALESCE(time_override,(SELECT CASE WHEN a.start_date IS NULL THEN '' ELSE
   CONCAT(CAST(a.start_date AS CHAR(10)),CASE WHEN a.end_date IS NULL OR a.end_date=a.start_date THEN ''
   ELSE CONCAT(' ~ ',CAST(a.end_date AS CHAR(10))) END) END FROM activity a WHERE a.id=p.activity_id),'');
CREATE TABLE activity_plan_review (
  plan_id BIGINT NOT NULL,
  reviewer_id BIGINT NOT NULL,
  name VARCHAR(255),
  approved BOOLEAN,
  comment TEXT,
  reviewed_at DATETIME,
  PRIMARY KEY (plan_id, reviewer_id),
  CONSTRAINT fk_plan_review_plan FOREIGN KEY (plan_id) REFERENCES activity_plan(id)
);
-- Preserve the explicitly recorded reviewer on legacy submissions; never infer reviewers by role.
INSERT INTO activity_plan_review(plan_id,reviewer_id,name,approved,comment,reviewed_at)
SELECT p.id,p.reviewer_id,u.real_name,
  CASE WHEN p.status='APPROVED' THEN TRUE WHEN p.status='REJECTED' THEN FALSE ELSE NULL END,
  p.review_comment,p.updated_at
FROM activity_plan p JOIN sys_user u ON u.id=p.reviewer_id AND u.org_id=p.org_id
WHERE p.reviewer_id IS NOT NULL;
-- Unassigned pending legacy submissions must be assigned explicitly by their author.
UPDATE activity_plan SET status='DRAFT' WHERE status='PENDING' AND NOT EXISTS
 (SELECT 1 FROM activity_plan_review r WHERE r.plan_id=activity_plan.id);
CREATE INDEX idx_plan_activity_version ON activity_plan(activity_id,version);
