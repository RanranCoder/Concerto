import { test, expect } from '@playwright/test'
import { Document, Packer, Paragraph, Table, TableRow, TableCell, WidthType } from 'docx'
import JSZip from 'jszip'
import { mkdir } from 'node:fs/promises'
import path from 'node:path'

test.use({ channel: process.platform === 'win32' ? 'msedge' : undefined, viewport: { width: 1440, height: 1000 }, baseURL: process.env.PAMS_SUPERDOC_URL || 'http://localhost:5173' })
test.setTimeout(180_000)
const output = path.resolve('../docs/design/superdoc-implementation')

test('native SuperDoc loads, edits, saves DOCX bytes and reloads the saved file', async ({ page }) => {
  await mkdir(output, { recursive: true })
  let bytes = Buffer.from(await Packer.toBuffer(new Document({ sections: [{ children: [
    new Paragraph({ text: '策划书原生文档验证', heading: 'Heading1' }),
    new Paragraph('这是可以直接编辑的策划书正文。'),
    new Table({ width: { size: 9000, type: WidthType.DXA }, columnWidths: [4500, 4500], rows: [new TableRow({ children: [new TableCell({ width: { size: 4500, type: WidthType.DXA }, children: [new Paragraph('预算项目')] }), new TableCell({ width: { size: 4500, type: WidthType.DXA }, children: [new Paragraph('120元')] })] })] }),
  ] }] })))
  let plan: Record<string, unknown> = { id: 11, activityId: 7, version: 1, revision: 0, ownerId: 1,
    documentFileId: 50, documentText: '策划书原生文档验证', status: 'DRAFT', reviewers: [], updatedAt: '2026-10-21T10:00:00' }
  const permissions = ['activity:view', 'activity:edit', 'activity:create', 'activity:review', 'material:view', 'file:view']
  const user = { id: 1, username: 'author', realName: '策划书作者', roleCode: 'TEACHER', roleLevel: 6,
    permissions, businessPermissions: permissions, managementCapabilities: [], orgId: 1, orgName: '文档验证组织', isOwner: true }
  const activity = { id: 7, name: 'SuperDoc 策划书验证', status: 'PLANNING', theme: '原生文档编辑', organizer: '组织部', startDate: '2026-10-21' }
  const errors: string[] = []
  page.on('pageerror', e => { errors.push(e.message); console.log('PAGE ERROR', e.message) })
  page.on('console', message => { if (message.type() === 'error') console.log('CONSOLE ERROR', message.text()) })
  const workspace = () => ({ latest: plan, currentId: 11, canEdit: true, currentHidden: false, versions: [plan], status: plan.status })
  await page.addInitScript(user => localStorage.setItem('pams_token', JSON.stringify({ state: { token: 'native-test', user }, version: 1 })), user)
  let uploads = 0, saves = 0
  let failSave = false
  let submitted: number[] = []
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), p = url.pathname
    if (!p.startsWith('/api/')) return route.continue()
    const ok = (data: unknown) => route.fulfill({ json: { code: 200, data } })
    if (p === '/api/auth/me') return ok(user)
    if (p === '/api/orgs/current') return ok({ id: 1, name: user.orgName, type: '党建办公室', enabledPacks: '["party"]', themeJson: '{}' })
    if (p === '/api/activities/7/detail') return ok({ activity, plan: workspace(), agendas: [], seatZones: {}, score: { rules: [], records: [] }, tasks: [], signinCount: 0 })
    if (p === '/api/activities/7') return ok(activity)
    if (p === '/api/plans/workspace') return ok(workspace())
    if (p === '/api/plans/reviewers') return ok([{ id: 2, name: '审核甲', deptName: '组织部', roleName: '部长' }])
    if (p === '/api/plans/11/submit') {
      submitted = request.postDataJSON().reviewerIds
      plan = { ...plan, status: 'PENDING', reviewers: [{ userId: 2, name: '审核甲', approved: null, comment: null }] }
      return ok(null)
    }
    if (p === '/api/plans/11/document') return route.fulfill({ body: bytes, contentType: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document' })
    if (p === '/api/files/upload') {
      const form = await new Response(request.postDataBuffer() as Uint8Array, { headers: { 'content-type': request.headers()['content-type'] } }).formData()
      bytes = Buffer.from(await (form.get('file') as File).arrayBuffer())
      uploads++
      return ok({ id: 51, filename: '策划书.docx' })
    }
    if (p === '/api/plans/11' && request.method() === 'PUT') {
      if (failSave) return route.fulfill({ status: 500, json: { code: 500, message: '验证保存失败' } })
      plan = { ...plan, ...request.postDataJSON(), revision: Number(plan.revision) + 1, updatedAt: '2026-10-21T11:00:00' }
      saves++
      return ok(null)
    }
    return ok([])
  })
  await page.goto('/activities/7?tab=plan')
  const engine = page.locator('[data-document-engine="superdoc"]')
  await expect(engine).toHaveAttribute('data-ready', 'true', { timeout: 120_000 })
  await expect(page.locator('[data-slate-editor],.w-e-toolbar,iframe[title="白色 A4 策划书文档"]')).toHaveCount(0)
  await page.screenshot({ path: path.join(output, 'superdoc-preview.png') })
  await page.getByRole('button', { name: /编辑策划/ }).click()
  await expect(engine).toHaveAttribute('data-ready', 'true', { timeout: 120_000 })
  await expect(page.locator('.pams-superdoc-toolbar')).toBeVisible()
  await page.screenshot({ path: path.join(output, 'superdoc-editor.png') })
  // Exercise real keyboard input against SuperDoc's document surface, not a mocked editor.
  const surface = engine.getByRole('textbox', { name: 'SuperDoc body (v2)', exact: true })
  await surface.getByText('这是可以直接编辑的策划书正文。', { exact: true }).first().click()
  await page.keyboard.press('End')
  await page.keyboard.insertText(' SUPERDOC_PERSIST_20261021')
  await expect(page.getByText('有未保存的修改')).toBeVisible()
  failSave = true
  await page.getByRole('button', { name: '保存策划书' }).click()
  await expect(page.getByText('保存未完成，您的修改仍保留在编辑器中，请重试')).toBeVisible({ timeout: 30_000 })
  await expect(page.getByRole('button', { name: '保存策划书' })).toBeEnabled()
  failSave = false
  await page.getByRole('button', { name: '保存策划书' }).click()
  await expect(page.getByText('正在编辑策划书')).toHaveCount(0, { timeout: 30_000 })
  expect(saves).toBe(1)
  expect(uploads).toBe(2)
  const zip = await JSZip.loadAsync(bytes)
  expect(await zip.file('word/document.xml')!.async('string')).toContain('SUPERDOC_PERSIST_20261021')
  await page.reload()
  await expect(engine).toHaveAttribute('data-ready', 'true', { timeout: 120_000 })
  await expect(page.locator('.pams-superdoc-toolbar')).toBeHidden()
  await page.screenshot({ path: path.join(output, 'superdoc-saved.png') })
  await page.getByRole('button', { name: '切换深色', exact: true }).click()
  await page.screenshot({ path: path.join(output, 'superdoc-dark.png') })
  await page.setViewportSize({ width: 390, height: 844 })
  await expect(engine).toHaveAttribute('data-ready', 'true')
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).toBe(true)
  await engine.scrollIntoViewIfNeeded()
  await page.screenshot({ path: path.join(output, 'superdoc-mobile.png') })
  await page.setViewportSize({ width: 1440, height: 1000 })
  await page.getByRole('button', { name: '切换亮色', exact: true }).click()
  await page.getByRole('button', { name: /编辑策划/ }).click()
  await expect(engine).toHaveAttribute('data-ready', 'true', { timeout: 120_000 })
  await engine.getByRole('textbox', { name: 'SuperDoc body (v2)', exact: true }).getByText(/这是可以直接编辑的策划书正文/).first().click()
  await page.keyboard.insertText(' SHOULD_DISCARD')
  await expect(page.getByText('有未保存的修改')).toBeVisible()
  await page.getByRole('button', { name: '取消编辑' }).click()
  await page.getByRole('button', { name: '放弃修改' }).click()
  await expect(engine).toHaveAttribute('data-ready', 'true', { timeout: 120_000 })
  expect(uploads).toBe(2)
  expect(await (await JSZip.loadAsync(bytes)).file('word/document.xml')!.async('string')).not.toContain('SHOULD_DISCARD')
  await page.getByRole('button', { name: '提交审核', exact: true }).click()
  await page.getByRole('checkbox', { name: /审核甲/ }).check()
  await page.getByRole('button', { name: '提交给 1 位审核人' }).click()
  await expect.poll(() => submitted).toEqual([2])
  await expect(page.getByRole('button', { name: /编辑策划/ })).toHaveCount(0)
  expect(errors).toEqual([])
})
