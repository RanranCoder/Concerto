import { App, Layout, Menu, Dropdown, Space, Avatar, Spin, Form, Input, Button } from 'antd'
import type { MenuProps } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Suspense, useMemo, startTransition, useState } from 'react'
import {
  ApartmentOutlined,
  BellOutlined,
  BookOutlined,
  CalendarOutlined,
  DashboardOutlined,
  ExperimentOutlined,
  FileTextOutlined,
  FolderOutlined,
  IdcardOutlined,
  LockOutlined,
  LogoutOutlined,
  RobotOutlined,
  ScheduleOutlined,
  TagsOutlined,
  TeamOutlined,
  TrophyOutlined,
  UserOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import { useAuthStore } from '@/stores/auth'
import { useOrgStore, isPackEnabled, term } from '@/stores/org'
import ThemeSwitch from '@/components/glass/ThemeSwitch'
import NotificationBell from '@/components/notification/NotificationBell'
import { NotificationToast } from '@/components/notification/NotificationToast'
import GlassModal from '@/components/glass/GlassModal'
import { changePassword } from '@/api/permission'

const { Sider, Header, Content } = Layout

export default function MainLayout() {
  const { message } = App.useApp()
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  // 领域包与术语（A2）：驱动菜单显隐与文案
  const packs = useOrgStore((s) => s.enabledPacks)
  const terminology = useOrgStore((s) => s.terminology)
  const navigate = useNavigate()
  const location = useLocation()

  // 修改密码弹窗
  const [pwdOpen, setPwdOpen] = useState(false)
  const [pwdSaving, setPwdSaving] = useState(false)
  const [pwdForm] = Form.useForm<{ oldPassword: string; newPassword: string; confirm: string }>()

  const handleChangePassword = async () => {
    const values = await pwdForm.validateFields()
    setPwdSaving(true)
    try {
      await changePassword(values.oldPassword, values.newPassword)
      message.success('密码已修改，请重新登录')
      setPwdOpen(false)
      logout()
      startTransition(() => navigate('/login', { replace: true }))
    } catch {
      /* http 拦截已提示 */
    } finally {
      setPwdSaving(false)
    }
  }

  // 菜单按权限码显隐（Task 26 + RBAC 接通）：
  // 有权限码的项读 user.permissions（与权限管理页配置一致）；无独立权限码的功能
  // （内容宣传、系统设置）仍按角色兜底。默认权限映射保证现有可见性不变。
  const menuItems = useMemo(() => {
    const roleCode = user?.roleCode
    const perms = user?.permissions ?? []
    const hasPerm = (code: string) => perms.includes(code)
    const isMinisterOrAbove = (user?.roleLevel ?? 0) >= 3
    const isAdmin = roleCode === 'TEACHER' || roleCode === 'DIRECTOR'

    const items: MenuProps['items'] = [
      { key: '/', label: '仪表盘', icon: <DashboardOutlined /> },
      // 灵思对所有登录用户可见；能做什么由工具层权限决定，不看菜单权限。
      // 「协作」不再单独占一个菜单项：它已内聚为灵思标准模式里自动启用的执行策略。
      { key: '/agent', label: '灵思', icon: <RobotOutlined /> },
    ]
    // 知识库（V27）：灵思「对话」档位能检索到的资料。
    // kb:read 种子给了全部角色 —— 与 /agent 同一立场：问答人人可用，
    // 能看到哪些内容由各知识源的可见范围决定，不由菜单权限决定。
    if (hasPerm('kb:read')) {
      items.push({ key: '/knowledge', label: '知识库', icon: <BookOutlined /> })
    }
    if (hasPerm('activity:view')) {
      items.push({ key: '/activities', label: term(terminology, 'activity', '活动管理'), icon: <CalendarOutlined /> })
    }
    if (hasPerm('schedule:view')) {
      items.push({
        key: 'routine',
        label: '排班考勤',
        icon: <ScheduleOutlined />,
        children: [
          { key: '/routine/schedules', label: '排班' },
          { key: '/routine/my-schedules', label: '我的排班' },
          { key: '/routine/activity-schedules', label: '活动排班' },
          { key: '/routine/attendance', label: '考勤' },
          ...(hasPerm('schedule:free_table') ? [
            { key: '/routine/course-schedule', label: '无课表制作' },
            { key: '/routine/free-schedules', label: '无课表' },
          ] : []),
        ],
      })
    }
    if (hasPerm('material:view')) {
      items.push({ key: '/archive/materials', label: '材料库', icon: <FolderOutlined /> })
    }
    // 党务台账路由守卫是 RequireRole(LEADER_ROLES) 不含 STAFF，菜单按角色对齐避免干事点进去 403
    // 党务台账 = party 领域包；组织未启用该包时整块隐藏（A2：按领域包装配）
    if (isMinisterOrAbove && isPackEnabled(packs, 'party')) {
      items.push({ key: '/party/members', label: term(terminology, 'partyLedger', '党务台账'), icon: <IdcardOutlined /> })
    }
    if (hasPerm('member:view')) {
      items.push({ key: '/members', label: '成员管理', icon: <IdcardOutlined /> })
    }
    if (isMinisterOrAbove) {
      items.push({
        key: 'content',
        label: '内容宣传',
        icon: <FileTextOutlined />,
        children: [
          { key: '/content/articles', label: '推文' },
          { key: '/content/news', label: '新闻稿' },
        ],
      })
    }
    if (hasPerm('template:view')) {
      items.push({ key: '/archive/templates', label: '模板库', icon: <TagsOutlined /> })
    }
    if (hasPerm('quality:view')) {
      items.push({ key: '/archive/credits', label: '素拓加分', icon: <TrophyOutlined /> })
    }
    if (hasPerm('notice:view')) {
      items.push({
        key: 'archive',
        label: '通知公告',
        icon: <BellOutlined />,
        children: [
          { key: '/archive/announcements', label: '公告' },
          { key: '/archive/group-chats', label: '群聊管理' },
        ],
      })
    }
    // 用户与权限：有 user:view 或 user:permission 任一即显示父菜单
    if (hasPerm('user:view') || hasPerm('user:permission')) {
      const adminChildren: MenuProps['items'] = []
      if (hasPerm('user:view')) adminChildren.push({ key: '/admin/users', label: '用户管理' })
      if (hasPerm('user:permission')) adminChildren.push({ key: '/admin/permissions', label: '权限管理' })
      items.push({
        key: 'admin',
        label: '用户与权限',
        icon: <TeamOutlined />,
        children: adminChildren,
      })
    }
    if (isAdmin) {
      items.push({ key: '/admin/settings', label: '系统设置', icon: <SettingOutlined /> })
      items.push({ key: '/admin/benchmark', label: '协作实验', icon: <ExperimentOutlined /> })
      items.push({ key: '/org/structure', label: '组织架构', icon: <ApartmentOutlined /> })
    }
    return items
  }, [user?.roleCode, user?.roleLevel, user?.permissions, packs, terminology])

  // 选中态：/routine/* → 排班考勤；/party/* → 党务台账；/content/* → 内容宣传子菜单项；
  // /archive/* → 材料库；/admin/users → 用户管理、/admin/permissions → 权限管理、
  // /admin/settings → 系统设置；/activities/* → 活动管理。
  // 注意：/content/news 等 query 参数不会进入 pathname，前缀匹配即可命中子菜单项。
  const selectedKey = useMemo(() => {
    const p = location.pathname
    if (p.startsWith('/agent')) return '/agent'
    if (p.startsWith('/routine/attendance')) return '/routine/attendance'
    if (p.startsWith('/routine/my-schedules')) return '/routine/my-schedules'
    if (p.startsWith('/routine/activity-schedules')) return '/routine/activity-schedules'
    if (p.startsWith('/routine/course-schedule')) return '/routine/course-schedule'
    if (p.startsWith('/routine/free-schedules')) return '/routine/free-schedules'
    if (p.startsWith('/routine')) return '/routine/schedules'
    if (p.startsWith('/party')) return '/party/members'
    if (p.startsWith('/members')) return '/members'
    if (p.startsWith('/content')) return p.startsWith('/content/news') ? '/content/news' : '/content/articles'
    if (p.startsWith('/archive/templates')) return '/archive/templates'
    if (p.startsWith('/archive/credits')) return '/archive/credits'
    if (p.startsWith('/archive/announcements')) return '/archive/announcements'
    if (p.startsWith('/archive/group-chats')) return '/archive/group-chats'
    if (p.startsWith('/archive')) return '/archive/materials'
    if (p === '/admin/users') return '/admin/users'
    if (p === '/admin/permissions') return '/admin/permissions'
    if (p === '/admin/settings') return '/admin/settings'
    if (p.startsWith('/org/structure')) return '/org/structure'
    if (p.startsWith('/activities')) return '/activities'
    return p
  }, [location.pathname])

  return (
    <Layout style={{ height: '100vh', overflow: 'hidden' }}>
      <NotificationToast />
      <Sider width={200} className="app-sider">
        <div className="app-brand">
          <img src="/hello-kitty.png" alt="" className="app-brand-logo" />
          <div className="app-brand-text">
            <div className="app-brand-name">党建工作台</div>
            <div className="app-brand-sub">信息与智能工程学院党建办公室</div>
          </div>
        </div>
        {/* 菜单独立滚动：品牌区固定在顶部不跟着滚 */}
        <div className="app-nav">
          <Menu
            mode="inline"
            selectedKeys={[selectedKey]}
            onClick={(e) => navigate(e.key)}
            items={menuItems}
            style={{ borderInlineEnd: 'none' }}
          />
        </div>
      </Sider>
      <Layout style={{ height: '100vh', minWidth: 0 }}>
        <Header className="app-header">
          <ThemeSwitch />
          <NotificationBell />
          <Dropdown
            menu={{
              items: [
                { key: 'change-pwd', icon: <LockOutlined />, label: '修改密码' },
                { type: 'divider' },
                { key: 'logout', icon: <LogoutOutlined />, label: '退出登录' },
              ],
              onClick: ({ key }) => {
                if (key === 'change-pwd') {
                  setPwdOpen(true)
                } else if (key === 'logout') {
                  logout()
                  startTransition(() => navigate('/login', { replace: true }))
                }
              },
            }}
          >
            <Space className="app-user">
              <Avatar size={26} style={{ background: 'var(--brand)' }} icon={<UserOutlined />} />
              <span>{user?.realName}（{user?.deptName ?? user?.roleCode}）</span>
            </Space>
          </Dropdown>
        </Header>
        <Content className="app-content">
          <Suspense
            fallback={
              <div style={{ textAlign: 'center', padding: 80 }}>
                <Spin />
              </div>
            }
          >
            {/* key 用 pathname：路由切换时整个页面重挂载，触发 .page-transition 淡入动画 */}
            <div key={location.pathname} className="page-transition content-inner">
              <Outlet />
            </div>
          </Suspense>
        </Content>
      </Layout>

      {/* 修改密码弹窗 */}
      <GlassModal
        title="修改密码"
        open={pwdOpen}
        onCancel={() => setPwdOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setPwdOpen(false)}>取消</Button>
            <Button type="primary" loading={pwdSaving} onClick={handleChangePassword}>
              确认修改
            </Button>
          </Space>
        }
      >
        <Form form={pwdForm} layout="vertical">
          <Form.Item
            name="oldPassword"
            label="当前密码"
            rules={[{ required: true, message: '请输入当前密码' }]}
          >
            <Input.Password autoComplete="current-password" placeholder="请输入当前密码" />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 6, message: '新密码长度不能少于 6 位' },
            ]}
          >
            <Input.Password autoComplete="new-password" placeholder="至少 6 位" />
          </Form.Item>
          <Form.Item
            name="confirm"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value) return Promise.resolve()
                  return Promise.reject(new Error('两次输入的新密码不一致'))
                },
              }),
            ]}
          >
            <Input.Password autoComplete="new-password" placeholder="再次输入新密码" />
          </Form.Item>
        </Form>
      </GlassModal>
    </Layout>
  )
}
