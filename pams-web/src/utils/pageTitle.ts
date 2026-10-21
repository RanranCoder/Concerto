/**
 * 标签页标题：统一为「协奏-{标签页名}」。
 * 与 MainLayout 的菜单选中态（selectedKey）保持同一套路由认知，但这里用静态表，
 * 因为标题对未登录页（/login、/register、/signin/:token）同样要生效，
 * 而菜单是登录后按权限渲染的，不能作为标题来源。
 */

/** 产品名，出现在标签页标题最前面 */
const APP_NAME = '协奏'

/** 路由命中不到时的兜底页名 */
const FALLBACK = '工作台'

/**
 * 顺序敏感：更具体的路径必须排在更宽泛的前面。
 * 例如 /activities/:id/edit 必须早于 /activities/:id，否则会先命中「活动详情」。
 */
const RULES: Array<[RegExp, string]> = [
  [/^\/$/, '工作台'],
  [/^\/agent(\/|$)/, '灵思'],
  [/^\/knowledge(\/|$)/, '知识库'],

  [/^\/activities\/[^/]+\/edit(\/|$)/, '编辑活动'],
  [/^\/activities\/[^/]+\/gantt(\/|$)/, '活动甘特图'],
  [/^\/activities\/[^/]+(\/|$)/, '活动详情'],
  [/^\/activities(\/|$)/, '活动管理'],

  [/^\/routine\/my-schedules(\/|$)/, '我的排班'],
  [/^\/routine\/activity-schedules(\/|$)/, '活动排班'],
  [/^\/routine\/attendance(\/|$)/, '考勤'],
  [/^\/routine\/course-schedule(\/|$)/, '无课表制作'],
  [/^\/routine\/free-schedules(\/|$)/, '无课表'],
  [/^\/routine\/schedules(\/|$)/, '排班'],

  [/^\/party\/members\/[^/]+(\/|$)/, '党员详情'],
  [/^\/party\/members(\/|$)/, '党务台账'],
  [/^\/party\/rosters(\/|$)/, '党员名册'],

  [/^\/members\/[^/]+(\/|$)/, '成员详情'],
  [/^\/members(\/|$)/, '成员管理'],

  [/^\/content\/articles(\/|$)/, '推文'],
  [/^\/content\/news(\/|$)/, '新闻稿'],

  [/^\/archive\/materials(\/|$)/, '材料库'],
  [/^\/archive\/templates(\/|$)/, '模板库'],
  [/^\/archive\/credits(\/|$)/, '素拓加分'],
  [/^\/archive\/announcements(\/|$)/, '公告'],
  [/^\/archive\/group-chats(\/|$)/, '群聊管理'],

  [/^\/notifications(\/|$)/, '通知中心'],
  [/^\/org\/structure(\/|$)/, '组织架构'],
  [/^\/admin\/users(\/|$)/, '用户管理'],
  [/^\/admin\/permissions(\/|$)/, '权限管理'],
  [/^\/admin\/settings(\/|$)/, '系统设置'],
  [/^\/admin\/benchmark(\/|$)/, '协作实验'],

  [/^\/login(\/|$)/, '登录'],
  [/^\/activate-account(\/|$)/, '激活账号'],
  [/^\/register(\/|$)/, '注册'],
  [/^\/signin(\/|$)/, '扫码签到'],
  [/^\/403(\/|$)/, '无权访问'],
]

/** pathname → 标签页名（不含产品名前缀） */
function resolvePageName(pathname: string): string {
  for (const [pattern, name] of RULES) {
    if (pattern.test(pathname)) return name
  }
  return FALLBACK
}

/** pathname → 完整标签页标题，形如「协奏-仪表盘」 */
export function resolveDocumentTitle(pathname: string): string {
  return `${APP_NAME}-${resolvePageName(pathname)}`
}
