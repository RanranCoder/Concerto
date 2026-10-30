import { test, expect, type Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { resolve } from 'node:path'

test.use({ channel: process.platform === 'win32' ? 'msedge' : undefined })
const user = { id: 1, username: 'memory-test', realName: '林老师', roleCode: 'DIRECTOR', roleLevel: 4, orgId: 1, permissions: ['activity:view', 'kb:read'], managementCapabilities: [], isOwner: false }
async function setup(page: Page, dark = false) {
  let authModule = ''
  page.on('request', request => { if(new URL(request.url()).pathname==='/src/stores/auth.ts')authModule=request.url() })
  let enabled = true
  const expiresAt = new Date(Date.now() + 90 * 86400000).toISOString()
  let rows = [
    { id: 'style', scope: 'USER', key: '风格', content: '先给结论，再说明依据。', status: 'ACTIVE', revision: 1, expiresAt },
    { id: 'candidate', scope: 'PROJECT:党建', key: '格式', content: '先列议程，再给分工。', status: 'PENDING', revision: 1, expiresAt },
    { id: 'old', scope: 'USER', key: '旧排班偏好', content: null as string | null, status: 'EXPIRED', revision: 2, expiresAt },
  ]
  let conflict = false
  const errors: string[] = []
  page.on('pageerror', error => errors.push(error.message))
  await page.addInitScript(({ user, dark }) => {
    localStorage.setItem('pams_token', JSON.stringify({ state: { token: 'memory-test-token', user }, version: 1 }))
    localStorage.setItem('pams_theme', JSON.stringify({ state: { mode: dark ? 'dark' : 'light' }, version: 0 }))
  }, { user, dark })
  await page.route(url => url.pathname.startsWith('/api/'), async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    let data: unknown = []
    if (path === '/api/auth/me') data = user
    else if (path === '/api/orgs/current') data = { id: 1, name: '党建办公室', themeJson: null }
    else if (path === '/api/notifications/unread-count') data = 0
    else if (path === '/api/agent/conversations') data = { records: [], total: 0, current: 1, size: 50, pendingTaskCount: 0 }
    else if (path === '/api/agent/memories/metrics') data = { summaryCalls: 0, summaryFailures: 0, summaryMeanMs: null, summaryWrites: 0, summaryCacheHits: 0, memoryHits: 0, memoryReads: 0, inputTokens: null, outputTokens: null, processLocal: true, usageSamples: 0 }
    else if (path === '/api/agent/memories/settings') { enabled = request.postDataJSON().enabled; data = null }
    else if (path.startsWith('/api/agent/memories/') && request.method() === 'DELETE') { rows = rows.filter(row => row.id !== path.split('/').at(-1)); data = null }
    else if (path === '/api/agent/memories' && request.method() === 'POST') {
      if (conflict) { await route.fulfill({ json: { code: 409, message: '记忆版本已变化，请重新查看' } }); return }
      const body = request.postDataJSON(), old = rows.find(row => row.scope === body.scope && row.key === body.key)
      const next = { ...body, id: old?.id ?? `new-${rows.length}`, status: 'ACTIVE', revision: (old?.revision ?? 0) + 1, expiresAt }
      rows = old ? rows.map(row => row.id === old.id ? next : row) : [...rows, next]; data = next
    } else if (path === '/api/agent/memories') data = { enabled, records: rows }
    await route.fulfill({ json: { code: 200, data } })
  })
  await page.goto('/agent')
  await page.getByRole('button', { name: '长期记忆', exact: true }).click()
  await expect(page.getByRole('heading', { name: '跨会话记住你的习惯' })).toBeVisible()
  await expect(page.getByText('先给结论，再说明依据。')).toBeVisible()
  return { errors, conflict: () => { conflict = true }, authModule: () => authModule }
}

test('切换组织时先清空旧记忆，再等待当前组织的数据', async ({ page }) => {
  const state=await setup(page)
  let release!:()=>void
  const gate=new Promise<void>(resolve=>{release=resolve})
  await page.route('**/api/agent/memories',async route=>{await gate;await route.fulfill({json:{code:200,data:{enabled:true,records:[]}}})})
  try {
    expect(state.authModule()).not.toBe('')
    await page.evaluate(async url=>{const {useAuthStore}=await import(url);useAuthStore.setState({user:{...useAuthStore.getState().user,orgId:2}})},state.authModule())
    await expect(page.getByText('先给结论，再说明依据。')).toHaveCount(0)
    await expect(page.getByText('正在读取你的记忆…')).toBeVisible()
  } finally {release()}
  await expect(page.getByRole('heading',{name:'还没有记忆',exact:true})).toBeVisible()
})

test('添加、编辑、候选确认、删除和开关完成一个管理流程', async ({ page }) => {
  const { errors } = await setup(page)
  await page.getByRole('button', { name: '添加记忆', exact: true }).click()
  await expect(page.getByLabel('记忆名称', { exact: true })).toBeFocused()
  await page.getByLabel('记忆名称', { exact: true }).fill('语言')
  await page.getByLabel('记住什么').fill('使用中文')
  await page.getByLabel('有效期（从保存时起，天）').fill('30')
  await page.getByRole('button', { name: '保存并启用' }).click()
  await expect(page.getByText('使用中文', { exact: true })).toBeVisible()
  const language = page.locator('.memory-list > li').filter({ has: page.getByRole('heading', { name: '语言', exact: true }) })
  await language.getByRole('button', { name: '编辑', exact: true }).click()
  await expect(page.getByLabel('记住什么')).toBeFocused()
  await page.getByLabel('记住什么').fill('使用简体中文')
  await page.getByRole('button', { name: '保存并启用' }).click()
  await expect(language.getByText('使用简体中文')).toBeVisible()
  await page.getByRole('button', { name: '确认候选', exact: true }).click()
  await expect(page.locator('.memory-list > li').filter({ hasText: '先列议程，再给分工。' }).getByText('已生效', { exact: true })).toBeVisible()
  await page.getByRole('switch', { name: '启用长期记忆' }).click()
  await expect(page.getByRole('button', { name: '添加记忆', exact: true })).toBeDisabled()
  await language.getByRole('button', { name: '删除', exact: true }).click()
  await language.getByRole('button', { name: '确认删除' }).click()
  await expect(page.getByText('使用简体中文', { exact: true })).toHaveCount(0)
  await page.getByRole('switch', { name: '启用长期记忆' }).click()
  await expect(page.getByRole('button', { name: '添加记忆', exact: true })).toBeEnabled()
  await page.getByLabel('搜索记忆').fill('找不到的偏好')
  await expect(page.getByRole('heading', { name: '没有匹配的记忆' })).toBeVisible()
  expect(errors).toEqual([])
})

test('版本冲突保留草稿并展示错误', async ({ page }) => {
  const state = await setup(page)
  await page.locator('.memory-list > li').filter({ hasText: '先给结论，再说明依据。' }).getByRole('button', { name: '编辑', exact: true }).click()
  await page.getByLabel('记住什么').fill('未保存的修改')
  state.conflict()
  await page.getByRole('button', { name: '保存并启用' }).click()
  await expect(page.getByRole('alert')).toContainText('记忆版本已变化')
  await expect(page.getByLabel('记住什么')).toHaveValue('未保存的修改')
})

for (const width of [1440, 768, 390]) for (const dark of [false, true]) test(`记忆面板布局 ${width} ${dark ? 'dark' : 'light'}`, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 })
  const { errors } = await setup(page, dark)
  await page.getByText('本次运行的效果与用量', { exact: true }).click()
  await expect(page.getByText('摘要输入 / 输出 token', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: '格式', exact: true })).toBeVisible()
  expect(await page.locator('.memory-sheet').evaluate(el => el.scrollWidth <= el.clientWidth)).toBe(true)
  const folder = resolve('..', '.deploy-helper', 'qa', 'screenshots'); mkdirSync(folder, { recursive: true })
  await page.screenshot({ path: resolve(folder, `memory-${width}-${dark ? 'dark' : 'light'}.png`), fullPage: true })
  if(width===1440&&!dark) {
    const html=await page.evaluate(()=>`<!doctype html><html lang="zh-CN" data-theme="light"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">${Array.from(document.head.querySelectorAll('style')).map(el=>el.outerHTML).join('')}<style>body{margin:0}.ant-drawer-content{max-width:680px;margin:auto;padding:24px;background:var(--canvas)}.ant-drawer-header{padding-bottom:20px}.ant-drawer-header-title{display:flex;gap:12px}</style></head><body>${document.querySelector('.lingsi-memory-drawer .ant-drawer-content')?.outerHTML}</body></html>`)
    writeFileSync(resolve(folder,'memory-static.html'),html)
  }
  expect(errors).toEqual([])
})
