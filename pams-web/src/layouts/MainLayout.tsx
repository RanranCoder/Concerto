import { Layout, Menu, Avatar, Dropdown, Space, Typography } from 'antd'
import { DashboardOutlined, LogoutOutlined, UserOutlined } from '@ant-design/icons'
import { Outlet, useNavigate, useLocation } from 'react-router-dom'
import { useAuthStore } from '@/stores/auth'
import ThemeSwitch from '@/components/glass/ThemeSwitch'

const { Header, Sider, Content } = Layout

export default function MainLayout() {
  const navigate = useNavigate()
  const { pathname } = useLocation()
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  const userMenu = {
    items: [
      { key: 'logout', icon: <LogoutOutlined />, label: '退出登录' },
    ],
    onClick: ({ key }: { key: string }) => {
      if (key === 'logout') handleLogout()
    },
  }

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider width={220} breakpoint="lg" collapsedWidth={0}>
        <div style={{ padding: 16, fontWeight: 600, fontSize: 16 }}>
          党务管理系统
        </div>
        <Menu
          mode="inline"
          selectedKeys={[pathname]}
          items={[{ key: '/', icon: <DashboardOutlined />, label: '仪表盘' }]}
          onClick={({ key }) => navigate(key)}
        />
      </Sider>
      <Layout>
        <Header style={{ display: 'flex', justifyContent: 'flex-end', alignItems: 'center', gap: 16, paddingInline: 24 }}>
          <ThemeSwitch />
          <Dropdown menu={userMenu}>
            <Space style={{ cursor: 'pointer' }}>
              <Avatar size="small" icon={<UserOutlined />} />
              <Typography.Text>{user?.realName ?? user?.username ?? '未登录'}</Typography.Text>
            </Space>
          </Dropdown>
        </Header>
        <Content style={{ padding: 24 }}>
          <Outlet />
        </Content>
      </Layout>
    </Layout>
  )
}
