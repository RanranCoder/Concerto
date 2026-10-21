import FormSurface from '@/components/form/FormSurface'
import {
  App,
  ConfigProvider,
  Drawer,
  Grid,
  Layout,
  Menu,
  Dropdown,
  Space,
  Avatar,
  Spin,
  Form,
  Input,
  Button,
} from 'antd'
import type { MenuProps } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Suspense, useEffect, useMemo, startTransition, useState } from 'react'
import {
  ApartmentOutlined,
  BookOutlined,
  CalendarOutlined,
  DashboardOutlined,
  ExperimentOutlined,
  FileTextOutlined,
  FolderOutlined,
  IdcardOutlined,
  LockOutlined,
  MenuOutlined,
  LogoutOutlined,
  RobotOutlined,
  ScheduleOutlined,
  TeamOutlined,
  UserOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import { useAuthStore } from '@/stores/auth'
import { useOrgStore, isPackEnabled, term } from '@/stores/org'
import ThemeSwitch from '@/components/glass/ThemeSwitch'
import NotificationBell from '@/components/notification/NotificationBell'
import { NotificationToast } from '@/components/notification/NotificationToast'
import { changePassword } from '@/api/permission'
import { buildBusinessTheme } from '@/styles/theme'
import BrandIdentity from '@/components/BrandIdentity'
import HeaderStaff from '@/components/HeaderStaff'

const { Sider, Header, Content } = Layout

export default function MainLayout() {
  const { message } = App.useApp()
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  // 领域包与术语（A2）：驱动菜单显隐与文案
  const packs = useOrgStore((s) => s.enabledPacks)
  const terminology = useOrgStore((s) => s.terminology)
  const orgName = useOrgStore((s) => s.orgName)
  const navigate = useNavigate()
  const location = useLocation()
  const screens = Grid.useBreakpoint()
  const [navOpen, setNavOpen] = useState(false)
  const [navOpenKeys, setNavOpenKeys] = useState<string[]>([])
  const agentLayout = location.pathname.startsWith('/agent')
  const preserveContentLayout = ['/knowledge', '/admin/benchmark'].some((path) =>
    location.pathname.startsWith(path),
  )
  const mobile = screens.md === false
  const businessTheme = useMemo(() => buildBusinessTheme(), [])

  // 修改密码弹窗
  const [pwdOpen, setPwdOpen] = useState(false)
  const [pwdSaving, setPwdSaving] = useState(false)
  const [pwdForm] = Form.useForm<{
    oldPassword: string
    newPassword: string
    confirm: string
  }>()

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
    const perms = user?.permissions ?? []
    const hasPerm = (code: string) => perms.includes(code)
    const isAdmin = hasPerm('org:manage')

    const items: MenuProps['items'] = [
      { key: '/', label: '工作台', icon: <DashboardOutlined /> },
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
      items.push({
        key: '/activities',
        label: term(terminology, 'activity', '活动管理'),
        icon: <CalendarOutlined />,
      })
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
          ...(hasPerm('schedule:free_table')
            ? [
                { key: '/routine/course-schedule', label: '无课表制作' },
                { key: '/routine/free-schedules', label: '无课表' },
              ]
            : []),
        ],
      })
    }
    // 党务台账路由守卫是 RequireRole(LEADER_ROLES) 不含 STAFF，菜单按角色对齐避免干事点进去 403
    // 党务台账 = party 领域包；组织未启用该包时整块隐藏（A2：按领域包装配）
    if (hasPerm('party:view') && isPackEnabled(packs, 'party')) {
      items.push({
        key: '/party/members',
        label: term(terminology, 'partyLedger', '党务台账'),
        icon: <IdcardOutlined />,
      })
    }
    if (hasPerm('member:view')) {
      items.push({
        key: '/members',
        label: '成员管理',
        icon: <IdcardOutlined />,
      })
    }
    if (hasPerm('content:view')) {
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
    const archiveChildren: MenuProps['items'] = []
    if (hasPerm('material:view'))
      archiveChildren.push({ key: '/archive/materials', label: '材料库' })
    if (hasPerm('template:view'))
      archiveChildren.push({ key: '/archive/templates', label: '模板库' })
    if (hasPerm('quality:view'))
      archiveChildren.push({ key: '/archive/credits', label: '素拓加分' })
    if (hasPerm('notice:view'))
      archiveChildren.push(
        { key: '/archive/announcements', label: '通知公告' },
        { key: '/archive/group-chats', label: '群聊管理' },
      )
    if (archiveChildren.length)
      items.push({
        key: 'archive',
        label: '材料与档案',
        icon: <FolderOutlined />,
        children: archiveChildren,
      })
    // 用户与权限：有 user:view 或 user:permission 任一即显示父菜单
    if (hasPerm('user:view') || hasPerm('role:view')) {
      const adminChildren: MenuProps['items'] = []
      if (hasPerm('user:view'))
        adminChildren.push({ key: '/admin/users', label: '用户管理' })
      if (hasPerm('role:view'))
        adminChildren.push({ key: '/admin/permissions', label: '权限管理' })
      items.push({
        key: 'admin',
        label: '用户与权限',
        icon: <TeamOutlined />,
        children: adminChildren,
      })
    }
    if (isAdmin) {
      items.push({
        key: '/admin/settings',
        label: '系统设置',
        icon: <SettingOutlined />,
      })
      items.push({
        key: '/admin/benchmark',
        label: '协作实验',
        icon: <ExperimentOutlined />,
      })
    }
    if (isAdmin || hasPerm('org:structure:manage')) {
      items.push({ key: '/org/structure', label: '组织架构', icon: <ApartmentOutlined /> })
    }
    return items
  }, [user?.roleCode, user?.roleLevel, user?.permissions, packs, terminology])

  // 对权限过滤后的入口分组，完整保留各业务子菜单。
  const groupedMenuItems = useMemo<MenuProps['items']>(() => {
    const groups = [
      { label: '日常工作', keys: ['/', '/activities', 'routine'] },
      {
        label: '组织与资料',
        keys: ['/party/members', '/members', 'content', 'archive'],
      },
      { label: '智能协作', keys: ['/agent', '/knowledge', '/admin/benchmark'] },
      {
        label: '系统管理',
        keys: ['admin', '/admin/settings', '/org/structure'],
      },
    ]
    return groups.flatMap(({ label, keys }) => {
      const children = keys.flatMap((key) => {
        const item = menuItems?.find((item) => item?.key === key)
        return item ? [item] : []
      })
      return children.length
        ? [
            {
              type: 'group' as const,
              key: label,
              label,
              className:
                label === '智能协作' ? 'sidebar-assistant-group' : undefined,
              children,
            },
          ]
        : []
    })
  }, [menuItems])

  // 选中态：/routine/* → 排班考勤；/party/* → 党务台账；/content/* → 内容宣传子菜单项；
  // /archive/* → 材料库；/admin/users → 用户管理、/admin/permissions → 权限管理、
  // /admin/settings → 系统设置；/activities/* → 活动管理。
  // 注意：/content/news 等 query 参数不会进入 pathname，前缀匹配即可命中子菜单项。
  const selectedKey = useMemo(() => {
    const p = location.pathname
    if (p.startsWith('/agent')) return '/agent'
    if (p.startsWith('/routine/attendance')) return '/routine/attendance'
    if (p.startsWith('/routine/my-schedules')) return '/routine/my-schedules'
    if (p.startsWith('/routine/activity-schedules'))
      return '/routine/activity-schedules'
    if (p.startsWith('/routine/course-schedule'))
      return '/routine/course-schedule'
    if (p.startsWith('/routine/free-schedules'))
      return '/routine/free-schedules'
    if (p.startsWith('/routine')) return '/routine/schedules'
    if (p.startsWith('/party')) return '/party/members'
    if (p.startsWith('/members')) return '/members'
    if (p.startsWith('/content'))
      return p.startsWith('/content/news')
        ? '/content/news'
        : '/content/articles'
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

  const activeMenuParent = menuItems?.find(
    (item) =>
      item &&
      'children' in item &&
      item.children?.some((child) => child?.key === selectedKey),
  )?.key
  useEffect(() => {
    if (activeMenuParent) {
      setNavOpenKeys((keys) =>
        keys.includes(String(activeMenuParent))
          ? keys
          : [...keys, String(activeMenuParent)],
      )
    }
  }, [activeMenuParent])

  const currentLabel = useMemo(() => {
    const find = (items: MenuProps['items']): string | undefined => {
      for (const item of items ?? []) {
        if (!item || !('key' in item)) continue
        if (
          item.key === selectedKey &&
          'label' in item &&
          typeof item.label === 'string'
        )
          return item.label
        if ('children' in item) {
          const label = find(item.children)
          if (label) return label
        }
      }
    }
    return find(menuItems) ?? '工作台'
  }, [menuItems, selectedKey])

  const navigation = (
    <div className="sidebar-v6">
      <div className="sidebar-brand">
        <BrandIdentity variant="blue" />
      </div>
      <div className="sidebar-organization">
        <small>
          {orgName === '党建办公室' ? '信息与智能工程学院' : '当前组织'}
        </small>
        <strong>{orgName || '当前组织'}</strong>
      </div>
      <nav className="sidebar-navigation" aria-label="主导航">
        <Menu
          mode="inline"
          selectedKeys={[selectedKey]}
          openKeys={navOpenKeys}
          onOpenChange={setNavOpenKeys}
          onClick={(event) => {
            navigate(event.key)
            setNavOpen(false)
          }}
          items={groupedMenuItems}
          inlineIndent={12}
          style={{ borderInlineEnd: 'none' }}
        />
      </nav>
      <div className="sidebar-account">
        <Avatar
          size={29}
          style={{
            background: 'var(--brand-soft)',
            color: 'var(--brand-text)',
          }}
        >
          {user?.realName?.slice(0, 1) || <UserOutlined />}
        </Avatar>
        <div>
          <strong>{user?.realName || '当前用户'}</strong>
          <small>
            {user?.deptName ||
              (
                {
                  TEACHER: '指导老师',
                  DIRECTOR: '主任',
                  ORG_LEADER: '组织部长',
                  SECRETARY_LEADER: '文秘部长',
                  MEDIA_LEADER: '新媒体部长',
                  TECH_LEADER: '青年科技部长',
                  STAFF: '干事',
                } as Record<string, string>
              )[user?.roleCode ?? ''] ||
              '组织成员'}
          </small>
        </div>
      </div>
    </div>
  )

  return (
    <Layout
      className="pams-shell pams-shell-v6"
      style={{ height: '100vh', overflow: 'hidden' }}
    >
      <NotificationToast />
      {!mobile && (
        <Sider width={216} className="app-sider">
          {navigation}
        </Sider>
      )}
      <Drawer
        className="business-navigation"
        title="导航"
        width={240}
        placement="left"
        open={navOpen && mobile}
        onClose={() => setNavOpen(false)}
        styles={{
          body: {
            padding: 0,
            display: 'flex',
            flexDirection: 'column',
            background: 'var(--sider-bg)',
          },
        }}
      >
        {navigation}
      </Drawer>
      <Layout style={{ height: '100vh', minWidth: 0 }}>
        <Header className="app-header">
          <HeaderStaff />
          {/* 内层容器与 .content-inner 同宽同轴：右边缘与内容列右边缘对齐 */}
          <div className="app-header-inner">
            <div className="business-breadcrumb">
              {mobile && (
                <Button
                  type="text"
                  icon={<MenuOutlined />}
                  aria-label="打开导航"
                  onClick={() => setNavOpen(true)}
                />
              )}
              <span className="business-breadcrumb-org">
                {orgName || '当前组织'}
              </span>
              <span className="business-breadcrumb-separator">/</span>
              <span>{currentLabel}</span>
            </div>
            <ThemeSwitch />
            <NotificationBell />
            <Dropdown
              trigger={['click']}
              placement="bottomRight"
              menu={{
                items: [
                  {
                    key: 'change-pwd',
                    icon: <LockOutlined />,
                    label: '修改密码',
                  },
                  { type: 'divider' },
                  {
                    key: 'logout',
                    icon: <LogoutOutlined />,
                    label: '退出登录',
                  },
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
              <Button
                type="text"
                className="app-user"
                aria-label="用户菜单"
                aria-haspopup="menu"
              >
                <Avatar
                  size={26}
                  style={{ background: 'var(--brand)' }}
                  icon={<UserOutlined />}
                />
                <span className="app-user-label">
                  {user?.realName}（{user?.deptName ?? user?.roleCode}）
                </span>
              </Button>
            </Dropdown>
          </div>
        </Header>
        <Content
          className={`app-content${agentLayout ? ' agent-content' : ''}`}
        >
          <Suspense
            fallback={
              <div style={{ textAlign: 'center', padding: 80 }}>
                <Spin />
              </div>
            }
          >
            {/* key 用 pathname：路由切换时整个页面重挂载，触发 .page-transition 淡入动画 */}
            <div
              key={location.pathname}
              className={`${preserveContentLayout ? 'page-transition content-inner' : 'page-transition content-inner ui-v6'}${agentLayout ? ' agent-content-inner' : ''}`}
            >
              {preserveContentLayout ? (
                <Outlet />
              ) : (
                <ConfigProvider theme={businessTheme}>
                  <Outlet />
                </ConfigProvider>
              )}
            </div>
          </Suspense>
        </Content>
      </Layout>

      {/* 修改密码弹窗 */}
      <FormSurface
        mode="dialog"
        title="修改密码"
        open={pwdOpen}
        onCancel={() => setPwdOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setPwdOpen(false)}>取消</Button>
            <Button
              type="primary"
              loading={pwdSaving}
              onClick={handleChangePassword}
            >
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
            <Input.Password
              autoComplete="current-password"
              placeholder="请输入当前密码"
            />
          </Form.Item>
          <Form.Item
            name="newPassword"
            label="新密码"
            rules={[
              { required: true, message: '请输入新密码' },
              { min: 6, message: '新密码长度不能少于 6 位' },
            ]}
          >
            <Input.Password
              autoComplete="new-password"
              placeholder="至少 6 位"
            />
          </Form.Item>
          <Form.Item
            name="confirm"
            label="确认新密码"
            dependencies={['newPassword']}
            rules={[
              { required: true, message: '请再次输入新密码' },
              ({ getFieldValue }) => ({
                validator(_, value) {
                  if (!value || getFieldValue('newPassword') === value)
                    return Promise.resolve()
                  return Promise.reject(new Error('两次输入的新密码不一致'))
                },
              }),
            ]}
          >
            <Input.Password
              autoComplete="new-password"
              placeholder="再次输入新密码"
            />
          </Form.Item>
        </Form>
      </FormSurface>
    </Layout>
  )
}
