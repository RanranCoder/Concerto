import { defineConfig, devices } from '@playwright/test'

/**
 * PRD-v5 Q2：E2E 冒烟链路。
 * 前置条件（需自行启动，本配置只托管前端 dev server）：
 *   1. MySQL 已运行且 pams_db 就绪（后端 Flyway 自动建表，dev profile 注入演示账号）
 *   2. 后端已启动：cd pams-backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev
 *      （或根目录 start.bat 一键拉起；后端需 PAMS_JWT_SECRET 环境变量）
 * 运行：npm run test:e2e（独立启动 13000 端口的 Vite 服务）
 */
const port = 13000
const host = '127.0.0.1'
const baseURL = `http://${host}:${port}`

export default defineConfig({
  testDir: './e2e',
  testIgnore: '**/batch6-real.spec.ts',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'line' : [['list']],
  use: {
    baseURL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'zh-CN',
    channel: process.env.PAMS_E2E_CHROME_CHANNEL as 'chrome' | undefined,
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: process.env.PAMS_NOTIFICATION_ACCEPTANCE_LIVE === '1' ? undefined : {
    command: `npm run dev -- --host ${host} --port ${port}`,
    url: baseURL,
    reuseExistingServer: false,
    timeout: 120_000,
  },
})
