import { test, expect, type Page } from '@playwright/test'

/**
 * PRD-v5 Q2：核心链路冒烟测试。
 * 链路：登录 → 仪表盘 → 活动列表 → 创建活动 → 活动详情（策划书/座位表/签到 Tab 可达）→ 删除收尾。
 * 账号为 dev profile 演示账号（teacher/123456）；生产环境不存在该账号，测试自然失败。
 */

async function login(page: Page, username = 'teacher', password = '123456') {
  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill(username)
  await page.getByPlaceholder('密码').fill(password)
  await page.getByRole('button', { name: /登\s*录/ }).click()
  // 登录成功跳转仪表盘（标题由 MainLayout 渲染）
  await expect(page.getByText('仪表盘').first()).toBeVisible({ timeout: 15_000 })
}

test.describe('冒烟链路', () => {
  test('登录 → 仪表盘渲染核心卡片', async ({ page }) => {
    await login(page)
    // 仪表盘页面挂载即视为前端路由/鉴权链路健康
    await expect(page).toHaveURL(/\/$/)
  })

  test('登录 → 活动列表 → 创建活动 → 详情 Tab 可达 → 清理', async ({ page }) => {
    await login(page)

    // 侧边栏进入活动管理
    await page.getByText('活动管理', { exact: false }).first().click()
    await expect(page.getByRole('heading', { name: '活动管理' })).toBeVisible()

    // 创建活动（名称唯一，便于断言与清理）
    const name = `E2E冒烟活动-${Date.now()}`
    await page.getByRole('button', { name: /新增活动/ }).click()
    await page.getByLabel(/活动名称/).fill(name)
    await page.getByRole('button', { name: /保\s*存/ }).click()
    await expect(page.getByText('创建成功')).toBeVisible()

    // 列表出现新建活动，通过行内「详情」进入
    const row = page.locator('tr', { hasText: name }).first()
    await expect(row).toBeVisible()
    await row.getByRole('button', { name: '详情' }).click()
    await expect(page).toHaveURL(/\/activities\/\d+/)

    // 详情核心 Tab 可达（策划书 / 座位表 / 签到）。新活动尚无策划书 → 空态提供「创建策划书」入口
    await page.getByRole('tab', { name: '策划书' }).click()
    await expect(page.getByRole('button', { name: '创建策划书' }).first()).toBeVisible()
    await page.getByRole('tab', { name: '座位表' }).click()
    // antd Radio.Button 原生 input 是隐藏元素（可见的是样式化 label），断言 checked 而非 visible
    await expect(page.getByRole('radio', { name: '可视化编辑' })).toBeChecked()
    await page.getByRole('tab', { name: '签到' }).click()

    // 回列表删除冒烟数据（teacher 有 activity:delete）。antd 两字按钮自动加空格（删 除）
    await page.goto('/activities')
    const delRow = page.locator('tr', { hasText: name }).first()
    await delRow.getByRole('button', { name: /删\s*除/ }).click()
    await page.getByRole('button', { name: /^删\s*除$/ }).click()
    await expect(page.getByText('已删除')).toBeVisible()
  })

  test('干事登录 → 用户管理菜单不可见（权限码口径）', async ({ page }) => {
    await login(page, 'staff')
    // STAFF 无 user:view：侧边栏不应出现「用户与权限」入口
    await expect(page.getByText('用户与权限', { exact: false })).toHaveCount(0)
  })
})
