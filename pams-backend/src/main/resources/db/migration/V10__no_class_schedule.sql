CREATE TABLE IF NOT EXISTS no_class_schedule (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  dept_id BIGINT,
  dept_name VARCHAR(50),
  semester VARCHAR(20) NOT NULL,
  grid_json TEXT NOT NULL,
  created_at DATETIME,
  UNIQUE KEY uk_no_class_dept_semester (dept_id, semester)
);
