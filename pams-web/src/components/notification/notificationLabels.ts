export const TYPE_LABEL: Record<string, string> = {
  TASK_ASSIGNED: '任务',
  TASK_CHANGED: '任务变更',
  TASK_COMPLETED: '任务完成',
  TASK_DUE_REMINDER: '任务临期',
  TASK_OVERDUE_REMINDER: '任务逾期',
  TASK_OVERDUE_DIGEST: '逾期摘要',
  SCHEDULE_START_REMINDER: '班前提醒',
  SCHEDULE_PUBLISHED: '排班发布',
  SCHEDULE_CHANGED: '排班调整',
  SCHEDULE_CANCELLED: '排班取消',
  PLAN_WITHDRAWN: '策划书撤回',
  COLLABORATION: '协作提醒',
  PLAN_SUBMITTED: '策划书',
  PLAN_APPROVED: '策划书',
  PLAN_REJECTED: '策划书',
  PLAN_MODIFIED: '策划书',
  SYSTEM: '系统',
  GOVERNANCE_CHANGED: '授权变更',
  QUALITY_SUMMARY: '素拓处理摘要',
  PARTY_SUMMARY: '党务处理摘要',
  IMPORT_RESULT: '导入结果',
  MAINTENANCE_ALERT: '维护告警',
  ORG_DIGEST: '每日资讯摘要',
  NEWS_UPLOADED: '新媒体',
  SIGNIN_ROSTER_UPLOADED: '签到',
  SIGNIN_COMPLETED: '签到',
  ARTICLE_SUBMITTED: '推文',
  ARTICLE_ASSIGNED: '推文',
  ARTICLE_APPROVED: '推文',
  ARTICLE_REJECTED: '推文',
  ARTICLE_PUBLISHED: '推文',
  ARTICLE_DEADLINE_REMINDER: '推文',
  ANNOUNCEMENT: '公告',
}

export function preferenceGroup(type: string) {
 if (/^(TASK_|PLAN_|COLLABORATION)/.test(type)) return '任务与活动'
 if (/^(ARTICLE_|NEWS_)/.test(type)) return '新媒体与推文'
 if (/^(SCHEDULE_|SIGNIN_)/.test(type)) return '排班与签到'
 return '组织与系统'
}
