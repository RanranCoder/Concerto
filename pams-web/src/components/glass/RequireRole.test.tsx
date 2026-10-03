// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import RequireRole, { RequirePerm, useHasPerm, LEADER_ROLES, ADMIN_ROLES } from './RequireRole'
import { useAuthStore } from '@/stores/auth'

/**
 * PRD-v5 Q1：路由权限守卫回归测试。
 * S2 双轨收敛后前端守卫（RequireRole/RequirePerm）与后端 hasAuthority 口径一致，
 * 这里固化两类守卫的放行/拦截行为，防止后续改动造成前后端权限口径漂移。
 */

function renderAt(path: string, element: React.ReactNode) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/" element={element} />
        <Route path="/403" element={<div>FORBIDDEN_PAGE</div>} />
      </Routes>
    </MemoryRouter>,
  )
}

function loginAs(roleCode: string, permissions: string[] = []) {
  useAuthStore.setState({
    token: 't',
    user: {
      id: 1,
      username: 'u',
      realName: '测试用户',
      roleCode,
      roleLevel: 3,
      deptId: null,
      deptName: null,
      orgId: null,
      orgName: null,
      permissions,
    },
  })
}

beforeEach(() => {
  useAuthStore.setState({ token: null, user: null })
})

describe('RequireRole 角色守卫', () => {
  it('未登录（无角色）→ 跳转 /403', () => {
    renderAt('/', <RequireRole roles={LEADER_ROLES}><div>CONTENT</div></RequireRole>)
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeTruthy()
    expect(screen.queryByText('CONTENT')).toBeNull()
  })

  it('角色不在白名单 → 跳转 /403（干事访问部长级页面）', () => {
    loginAs('STAFF')
    renderAt('/', <RequireRole roles={LEADER_ROLES}><div>CONTENT</div></RequireRole>)
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeTruthy()
  })

  it('角色在白名单 → 渲染内容（组织部长访问部长级页面）', () => {
    loginAs('ORG_LEADER')
    renderAt('/', <RequireRole roles={LEADER_ROLES}><div>CONTENT</div></RequireRole>)
    expect(screen.getByText('CONTENT')).toBeTruthy()
  })

  it('ADMIN_ROLES 仅含 TEACHER/DIRECTOR（用户管理与后端 user:view 口径一致）', () => {
    expect(ADMIN_ROLES).toEqual(['TEACHER', 'DIRECTOR'])
  })
})

describe('RequirePerm 权限码守卫', () => {
  it('无权限码集合 → 跳转 /403', () => {
    loginAs('STAFF', [])
    renderAt('/', <RequirePerm codes={['user:view']}><div>CONTENT</div></RequirePerm>)
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeTruthy()
  })

  it('缺少指定权限码 → 跳转 /403（部长无 user:view，S2 收敛口径）', () => {
    loginAs('ORG_LEADER', ['activity:view', 'activity:edit'])
    renderAt('/', <RequirePerm codes={['user:view']}><div>CONTENT</div></RequirePerm>)
    expect(screen.getByText('FORBIDDEN_PAGE')).toBeTruthy()
  })

  it('拥有任一权限码 → 渲染内容', () => {
    loginAs('DIRECTOR', ['activity:view', 'user:view'])
    renderAt('/', <RequirePerm codes={['user:view']}><div>CONTENT</div></RequirePerm>)
    expect(screen.getByText('CONTENT')).toBeTruthy()
  })
})

describe('useHasPerm 按钮显隐判定', () => {
  function Probe({ code }: { code: string }) {
    const hasPerm = useHasPerm()
    return <div>{hasPerm(code) ? 'VISIBLE' : 'HIDDEN'}</div>
  }

  it('按权限码集合返回判定函数', () => {
    loginAs('TEACHER', ['activity:delete', 'material:view'])
    const { rerender } = render(<Probe code="activity:delete" />)
    expect(screen.getByText('VISIBLE')).toBeTruthy()

    rerender(<Probe code="material:delete" />)
    expect(screen.getByText('HIDDEN')).toBeTruthy()
  })

  it('permissions 未定义（老缓存迁移）时安全降级为无权限', () => {
    useAuthStore.setState({ token: 't', user: null })
    render(<Probe code="activity:view" />)
    expect(screen.getByText('HIDDEN')).toBeTruthy()
  })
})
