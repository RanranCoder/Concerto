import { Layout, Menu, Dropdown, Space, Avatar, Typography } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
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
} from '@ant-design/icons'
import { useAuthStore } from '@/stores/auth'
import ThemeSwitch from '@/components/glass/ThemeSwitch'
import { useMemo } from 'react'

const { Sider, Header, Content } = Layout

export default function MainLayout() {
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
  const navigate = useNavigate()
  const location = useLocation()

  // 完整菜单（当前所有登录用户可见）；按角色过滤留给 Task 26
  const menuItems = useMemo(
    () => [
      { key: '/', label: '仪表盘', icon: <DashboardOutlined /> },
      { key: '/activities', label: '活动管理', icon: <CalendarOutlined /> },
      { key: '/routine/schedules', label: '排班考勤', icon: <ScheduleOutlined /> },
      { key: '/party/members', label: '党务台账', icon: <IdcardOutlined /> },
      { key: '/content/articles', label: '内容宣传', icon: <FileTextOutlined /> },
      { key: '/archive/materials', label: '材料库', icon: <FolderOutlined /> },
      { key: '/archive/templates', label: '模板库', icon: <TagsOutlined /> },
      { key: '/archive/credits', label: '素拓加分', icon: <TrophyOutlined /> },
      { key: '/archive/announcements', label: '通知公告', icon: <BellOutlined /> },
      { key: '/admin/users', label: '用户管理', icon: <TeamOutlined /> },
    ],
    [],
  )

  // 选中态：/routine/* → 排班考勤；/party/* → 党务台账；/content/* → 内容宣传；/archive/* → 材料库；/admin/* → 用户管理
  const selectedKey = useMemo(() => {
    const p = location.pathname
    if (p.startsWith('/routine')) return '/routine/schedules'
    if (p.startsWith('/party')) return '/party/members'
    if (p.startsWith('/content')) return '/content/articles'
    if (p.startsWith('/archive')) return '/archive/materials'
    if (p.startsWith('/admin')) return '/admin/users'
    if (p.startsWith('/activities')) return '/activities'
    return p
  }, [location.pathname])

  return (
    <Layout style={{ minHeight: '100vh' }}>
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
          theme="dark"
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
          <Dropdown
            menu={{
              items: [{ key: 'logout', icon: <LogoutOutlined />, label: '退出登录' }],
              onClick: ({ key }) => {
                if (key === 'logout') {
                  logout()
                  navigate('/login', { replace: true })
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
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
