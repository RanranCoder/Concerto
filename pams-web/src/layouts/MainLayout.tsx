import { Layout, Menu, Dropdown, Space, Avatar, Typography, Spin } from 'antd'
import type { MenuProps } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Suspense, useMemo, startTransition } from 'react'
import {
  BellOutlined,
  CalendarOutlined,
  DashboardOutlined,
  FileTextOutlined,
  FolderOutlined,
  IdcardOutlined,
  LogoutOutlined,
  ScheduleOutlined,
  TagsOutlined,
  TeamOutlined,
  TrophyOutlined,
  UserOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import { useAuthStore } from '@/stores/auth'
import ThemeSwitch from '@/components/glass/ThemeSwitch'
import NotificationBell from '@/components/notification/NotificationBell'
import { NotificationToast } from '@/components/notification/NotificationToast'

const { Sider, Header, Content } = Layout

export default function MainLayout() {
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  const navigate = useNavigate()
  const location = useLocation()

  // 菜单按角色过滤（Task 26）：
  // - 干事：仪表盘 / 活动管理 / 排班考勤 / 材料库
  // - 部长及以上：+ 党务台账 / 内容宣传(推文+新闻稿) / 模板库 / 素拓加分 / 通知公告
  // - 主任 / 指导老师：+ 用户管理（后端 @PreAuthorize 为部长以上，前端按简报口径仅主任+显示）
  const menuItems = useMemo(() => {
    const roleCode = user?.roleCode
    const isMinisterOrAbove = (user?.roleLevel ?? 0) >= 3
    const isAdmin = roleCode === 'TEACHER' || roleCode === 'DIRECTOR'

    const items: MenuProps['items'] = [
      { key: '/', label: '仪表盘', icon: <DashboardOutlined /> },
      { key: '/activities', label: '活动管理', icon: <CalendarOutlined /> },
      { key: '/routine/schedules', label: '排班考勤', icon: <ScheduleOutlined /> },
      { key: '/archive/materials', label: '材料库', icon: <FolderOutlined /> },
    ]
    if (isMinisterOrAbove) {
      items.push({ key: '/party/members', label: '党务台账', icon: <IdcardOutlined /> })
      items.push({
        key: 'content',
        label: '内容宣传',
        icon: <FileTextOutlined />,
        children: [
          { key: '/content/articles', label: '推文' },
          { key: '/content/news', label: '新闻稿' },
        ],
      })
      items.push({ key: '/archive/templates', label: '模板库', icon: <TagsOutlined /> })
      items.push({ key: '/archive/credits', label: '素拓加分', icon: <TrophyOutlined /> })
      items.push({ key: '/archive/announcements', label: '通知公告', icon: <BellOutlined /> })
    }
    if (isAdmin) {
      items.push({ key: '/admin/users', label: '用户管理', icon: <TeamOutlined /> })
      items.push({ key: '/admin/settings', label: '系统设置', icon: <SettingOutlined /> })
    }
    return items
  }, [user?.roleCode, user?.roleLevel])

  // 选中态：/routine/* → 排班考勤；/party/* → 党务台账；/content/* → 内容宣传子菜单项；
  // /archive/* → 材料库；/admin/users → 用户管理、/admin/settings → 系统设置；/activities/* → 活动管理。
  // 注意：/content/news 等 query 参数不会进入 pathname，前缀匹配即可命中子菜单项。
  const selectedKey = useMemo(() => {
    const p = location.pathname
    if (p.startsWith('/routine')) return '/routine/schedules'
    if (p.startsWith('/party')) return '/party/members'
    if (p.startsWith('/content')) return p.startsWith('/content/news') ? '/content/news' : '/content/articles'
    if (p.startsWith('/archive')) return '/archive/materials'
    if (p === '/admin/users') return '/admin/users'
    if (p === '/admin/settings') return '/admin/settings'
    if (p.startsWith('/activities')) return '/activities'
    return p
  }, [location.pathname])

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <NotificationToast />
      <Sider
        width={220}
        className="glass-card"
        style={{ margin: 12, borderRadius: 16, overflow: 'hidden' }}
      >
        <div style={{ padding: '20px 16px' }}>
          <Typography.Title level={5} style={{ color: 'var(--color-text)', margin: 0 }}>
            党务管理系统
          </Typography.Title>
          <Typography.Text style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
            信息与智能工程学院党建办公室
          </Typography.Text>
        </div>
        <Menu
          mode="inline"
          selectedKeys={[selectedKey]}
          onClick={(e) => navigate(e.key)}
          items={menuItems}
          style={{ background: 'transparent', borderInlineEnd: 'none' }}
        />
      </Sider>
      <Layout>
        <Header
          style={{
            background: 'transparent',
            padding: '0 24px',
            display: 'flex',
            justifyContent: 'flex-end',
            alignItems: 'center',
            gap: 12,
          }}
        >
          <ThemeSwitch />
          <NotificationBell />
          <Dropdown
            menu={{
              items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录' }],
              onClick: ({ key }) => {
                if (key === 'logout') {
                  logout()
                  startTransition(() => navigate('/login', { replace: true }))
                }
              },
            }}
          >
            <Space style={{ cursor: 'pointer', color: 'var(--color-text)' }}>
              <Avatar style={{ background: 'var(--color-red)' }} icon={<UserOutlined />} />
              <span>{user?.realName}（{user?.deptName ?? user?.roleCode}）</span>
            </Space>
          </Dropdown>
        </Header>
        <Content style={{ padding: '0 24px 24px' }}>
          <Suspense
            fallback={
              <div style={{ textAlign: 'center', padding: 80 }}>
                <Spin />
              </div>
            }
          >
            {/* key 用 pathname：路由切换时整个页面重挂载，触发 .page-transition 淡入动画 */}
            <div key={location.pathname} className="page-transition">
              <Outlet />
            </div>
          </Suspense>
        </Content>
      </Layout>
    </Layout>
  )
}
