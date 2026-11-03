import { defineConfig, devices } from '@playwright/test'

export default defineConfig({
  testDir: './e2e', testMatch: 's5-real.spec.ts', workers: 1, retries: 0,
  timeout: 180_000, expect: { timeout: 60_000 }, reporter: [['list']],
  outputDir: 'test-results-s5',
  use: { ...devices['Desktop Chrome'], baseURL: 'http://127.0.0.1:5185',
    channel: process.platform === 'win32' ? 'msedge' : undefined,
    locale: 'zh-CN', timezoneId: 'Asia/Shanghai', screenshot: 'only-on-failure', trace: 'retain-on-failure' },
  webServer: {
    command: 'npm run dev -- --host 127.0.0.1 --port 5185 --strictPort',
    url: 'http://127.0.0.1:5185', reuseExistingServer: false, timeout: 120_000,
    env: { PAMS_E2E_API_TARGET: 'http://127.0.0.1:18080' },
  },
})
