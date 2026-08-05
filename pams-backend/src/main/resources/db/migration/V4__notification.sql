-- ===================== 4、业务通知 =====================
CREATE TABLE IF NOT EXISTS notification (
  id               BIGINT AUTO_INCREMENT PRIMARY KEY,
  type             VARCHAR(30)  NOT NULL COMMENT '通知类型: TASK_ASSIGNED/PLAN_SUBMITTED/PLAN_APPROVED/PLAN_REJECTED',
  title            VARCHAR(100) NOT NULL COMMENT '通知标题',
  content          VARCHAR(500) NOT NULL COMMENT '通知内容详情',
  entity_type      VARCHAR(20)  COMMENT '关联实体类型: TASK/PLAN/ACTIVITY',
  entity_id        BIGINT       COMMENT '关联实体ID',
  sender_id        BIGINT       COMMENT '发送者用户ID',
  recipient_id     BIGINT       COMMENT '接收者用户ID(个人定向)',
  recipient_role   VARCHAR(30)  COMMENT '接收角色(角色定向)',
  recipient_dept_id BIGINT      COMMENT '接收部门(部门定向)',
  is_read          TINYINT DEFAULT 0 COMMENT '已读标记: 0未读 1已读',
  read_at          DATETIME     COMMENT '阅读时间',
  created_at       DATETIME DEFAULT CURRENT_TIMESTAMP,
  deleted          TINYINT DEFAULT 0,
  INDEX idx_notification_recipient (recipient_id, is_read, created_at),
  INDEX idx_notification_role (recipient_role, is_read, created_at),
  INDEX idx_notification_dept (recipient_dept_id, is_read, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='业务通知';
