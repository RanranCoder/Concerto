import { defineConfig, devices } from '@playwright/test'

/**
 * PRD-v5 Q2：E2E 冒烟链路。
 * 前置条件（需自行启动，本配置只托管前端 dev server）：
 *   1. MySQL 已运行且 pams_db 就绪（后端 Flyway 自动建表，dev profile 注入演示账号）
 *   2. 后端已启动：cd pams-backend && mvn spring-boot:run -Dspring-boot.run.profiles=dev
 *      （或根目录 start.bat 一键拉起；后端需 PAMS_JWT_SECRET 环境变量）
 * 运行：npm run test:e2e（若 5173 已有 dev server 会直接复用）
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 60_000,
  expect: { timeout: 10_000 },
  fullyParallel: false,
  workers: 1,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'line' : [['list']],
  use: {
    baseURL: 'http://localhost:5173',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    locale: 'zh-CN',
  },
  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
})
