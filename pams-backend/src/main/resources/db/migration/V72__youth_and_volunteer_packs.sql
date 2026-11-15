CREATE TABLE youth_member (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    duty VARCHAR(60),
    joined_on DATE,
    version INT NOT NULL DEFAULT 0,
    UNIQUE (org_id, member_id)
);

CREATE TABLE youth_branch_change (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL,
    youth_member_id BIGINT NOT NULL,
    from_branch_id BIGINT,
    to_branch_id BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    changed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_youth_branch_history ON youth_branch_change(org_id, youth_member_id, id);

CREATE TABLE youth_life (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    kind VARCHAR(24) NOT NULL,
    agenda TEXT NOT NULL,
    minutes TEXT,
    archived_by BIGINT,
    archived_at DATETIME,
    version INT NOT NULL DEFAULT 0,
    UNIQUE (org_id, activity_id)
);

CREATE TABLE youth_case (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL,
    youth_member_id BIGINT NOT NULL,
    branch_id BIGINT NOT NULL,
    kind VARCHAR(24) NOT NULL,
    year_no INT NOT NULL,
    criteria TEXT,
    submission TEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    submitted_by BIGINT NOT NULL,
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    decision TEXT,
    reviewed_by BIGINT,
    reviewed_at DATETIME,
    party_member_id BIGINT,
    received_by BIGINT,
    received_at DATETIME,
    version INT NOT NULL DEFAULT 0,
    UNIQUE (org_id, youth_member_id, kind, year_no)
);
CREATE INDEX idx_youth_case_list ON youth_case(org_id, kind, id);
CREATE INDEX idx_youth_party_receipts ON youth_case(org_id, party_member_id, id);

CREATE TABLE youth_case_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL,
    case_id BIGINT NOT NULL,
    action VARCHAR(24) NOT NULL,
    note TEXT,
    actor_id BIGINT NOT NULL,
    occurred_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX idx_youth_case_event ON youth_case_event(org_id, case_id, id);

CREATE TABLE volunteer_activity (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL,
    activity_id BIGINT NOT NULL,
    max_minutes INT NOT NULL,
    UNIQUE (org_id, activity_id)
);

CREATE TABLE volunteer_record (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    org_id BIGINT NOT NULL,
    volunteer_activity_id BIGINT NOT NULL,
    member_id BIGINT NOT NULL,
    student_no VARCHAR(50) NOT NULL,
    signin_id BIGINT,
    signin_at DATETIME,
    service_minutes INT,
    status VARCHAR(24) NOT NULL DEFAULT 'REGISTERED',
    submitted_by BIGINT,
    submitted_at DATETIME,
    decision TEXT,
    reviewed_by BIGINT,
    reviewed_at DATETIME,
    version INT NOT NULL DEFAULT 0,
    UNIQUE (org_id, volunteer_activity_id, member_id),
    UNIQUE (org_id, volunteer_activity_id, student_no)
);
CREATE INDEX idx_volunteer_review ON volunteer_record(org_id, volunteer_activity_id, status, id);
CREATE INDEX idx_signin_pack_match ON signin(org_id, activity_id, student_no, sign_time);

INSERT INTO permission(code, name, module, category, sort_order) VALUES
    ('youth:view', '查看团务工作', 'youth', 'BUSINESS', 10),
    ('youth:manage', '维护团务记录', 'youth', 'BUSINESS', 20),
    ('youth:review', '审核评议与推优', 'youth', 'BUSINESS', 30),
    ('volunteer:view', '查看志愿服务', 'volunteer', 'BUSINESS', 10),
    ('volunteer:manage', '登记志愿服务', 'volunteer', 'BUSINESS', 20),
    ('volunteer:review', '审核与导出服务时长', 'volunteer', 'BUSINESS', 30);
