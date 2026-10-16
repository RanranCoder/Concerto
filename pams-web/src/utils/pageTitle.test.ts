import { describe, it, expect } from 'vitest'
import { resolveDocumentTitle } from './pageTitle'

describe('resolveDocumentTitle', () => {
  it('统一为「协奏-{标签页名}」', () => {
    expect(resolveDocumentTitle('/')).toBe('协奏-工作台')
    expect(resolveDocumentTitle('/agent')).toBe('协奏-灵思')
    expect(resolveDocumentTitle('/login')).toBe('协奏-登录')
  })

  it('动态段：更具体的规则优先于宽泛规则', () => {
    expect(resolveDocumentTitle('/activities')).toBe('协奏-活动管理')
    expect(resolveDocumentTitle('/activities/12')).toBe('协奏-活动详情')
    expect(resolveDocumentTitle('/activities/12/edit')).toBe('协奏-编辑活动')
    expect(resolveDocumentTitle('/activities/12/gantt')).toBe('协奏-活动甘特图')
  })

  it('前缀相近的路由不会互相误匹配', () => {
    // /members 与 /party/members 是两条不同路由
    expect(resolveDocumentTitle('/members')).toBe('协奏-成员管理')
    expect(resolveDocumentTitle('/members/7')).toBe('协奏-成员详情')
    expect(resolveDocumentTitle('/party/members')).toBe('协奏-党务台账')
    expect(resolveDocumentTitle('/party/members/7')).toBe('协奏-党员详情')
    // /routine 子路由各自独立
    expect(resolveDocumentTitle('/routine/my-schedules')).toBe('协奏-我的排班')
    expect(resolveDocumentTitle('/routine/schedules')).toBe('协奏-排班')
  })

  it('尾部斜杠与未知路由', () => {
    expect(resolveDocumentTitle('/agent/')).toBe('协奏-灵思')
    expect(resolveDocumentTitle('/not-a-real-route')).toBe('协奏-工作台')
  })
})
