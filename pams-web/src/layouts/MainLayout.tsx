import { App, Layout, Menu, Dropdown, Space, Avatar, Typography, Spin, Form, Input, Button } from 'antd'
import type { MenuProps } from 'antd'
import { Outlet, useLocation, useNavigate } from 'react-router-dom'
import { Suspense, useMemo, startTransition, useState } from 'react'
import {
  BellOutlined,
  CalendarOutlined,
  DashboardOutlined,
  FileTextOutlined,
  FolderOutlined,
  IdcardOutlined,
  LockOutlined,
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
import GlassModal from '@/components/glass/GlassModal'
import { changePassword } from '@/api/permission'

const { Sider, Header, Content } = Layout

export default function MainLayout() {
  const { message } = App.useApp()
  const user = useAuthStore((s) => s.user)
  const logout = useAuthStore((s) => s.logout)
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
      {
        key: 'routine',
        label: '排班考勤',
        icon: <ScheduleOutlined />,
        children: [
          { key: '/routine/schedules', label: '排班' },
          { key: '/routine/attendance', label: '考勤' },
          { key: '/routine/course-schedule', label: '无课表制作' },
          { key: '/routine/free-schedules', label: '无课表' },
        ],
      },
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
    if (isAdmin) {
      items.push({
        key: 'admin',
        label: '用户与权限',
        icon: <TeamOutlined />,
        children: [
          { key: '/admin/users', label: '用户管理' },
          // 权限管理仅指导老师（PRD F07.2），主任隐藏入口避免 403
          ...(roleCode === 'TEACHER' ? [{ key: '/admin/permissions', label: '权限管理' }] : []),
        ],
      })
      items.push({ key: '/admin/settings', label: '系统设置', icon: <SettingOutlined /> })
    }
    return items
  }, [user?.roleCode, user?.roleLevel])

  // 选中态：/routine/* → 排班考勤；/party/* → 党务台账；/content/* → 内容宣传子菜单项；
  // /archive/* → 材料库；/admin/users → 用户管理、/admin/permissions → 权限管理、
  // /admin/settings → 系统设置；/activities/* → 活动管理。
  // 注意：/content/news 等 query 参数不会进入 pathname，前缀匹配即可命中子菜单项。
  const selectedKey = useMemo(() => {
    const p = location.pathname
    if (p.startsWith('/routine/attendance')) return '/routine/attendance'
    if (p.startsWith('/routine/course-schedule')) return '/routine/course-schedule'
    if (p.startsWith('/routine/free-schedules')) return '/routine/free-schedules'
    if (p.startsWith('/routine')) return '/routine/schedules'
    if (p.startsWith('/party')) return '/party/members'
    if (p.startsWith('/content')) return p.startsWith('/content/news') ? '/content/news' : '/content/articles'
    if (p.startsWith('/archive/templates')) return '/archive/templates'
    if (p.startsWith('/archive/credits')) return '/archive/credits'
    if (p.startsWith('/archive/announcements')) return '/archive/announcements'
    if (p.startsWith('/archive/group-chats')) return '/archive/group-chats'
    if (p.startsWith('/archive')) return '/archive/materials'
    if (p === '/admin/users') return '/admin/users'
    if (p === '/admin/permissions') return '/admin/permissions'
    if (p === '/admin/settings') return '/admin/settings'
    if (p.startsWith('/activities')) return '/activities'
    return p
  }, [location.pathname])

  return (
    <Layout style={{ height: '100vh', overflow: 'hidden' }}>
      <NotificationToast />
      <Sider
        width={220}
        className="glass-card"
        style={{
          height: 'calc(100vh - 24px)',
          margin: 12,
          borderRadius: 16,
          overflowY: 'auto',
        }}
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
      <Layout style={{ height: '100vh', minWidth: 0 }}>
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
            <Space style={{ cursor: 'pointer', color: 'var(--color-text)' }}>
              <Avatar style={{ background: 'var(--color-red)' }} icon={<UserOutlined />} />
              <span>{user?.realName}（{user?.deptName ?? user?.roleCode}）</span>
            </Space>
          </Dropdown>
        </Header>
        <Content
          style={{
            minHeight: 0,
            overflowY: 'auto',
            padding: '0 24px 24px',
          }}
        >
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
