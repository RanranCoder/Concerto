import { defineConfig, devices } from '@playwright/test'
import { resolve } from 'node:path'
const output = process.env.PAMS_BATCH6_BROWSER_OUTPUT
if (!output) throw new Error('Requires disposable batch6 launcher')
export default defineConfig({
  testDir: './e2e', testMatch: 'batch6-real.spec.ts', workers: 1, retries: 0,
  timeout: 90_000, expect: { timeout: 20_000 },
  reporter: [['list'], ['json', { outputFile: resolve(output, 'browser-results.json') }]],
  outputDir: resolve(output, 'browser-artifacts'),
  use: { ...devices['Desktop Chrome'], baseURL: 'http://127.0.0.1:5185',
    channel: process.platform === 'win32' ? 'msedge' : undefined,
    locale: 'zh-CN', timezoneId: 'Asia/Shanghai', screenshot: 'only-on-failure', trace: 'retain-on-failure' },
  webServer: { command: 'npm run dev -- --host 127.0.0.1 --port 5185 --strictPort',
    url: 'http://127.0.0.1:5185', reuseExistingServer: false, timeout: 120_000,
    env: { PAMS_E2E_API_TARGET: 'http://127.0.0.1:18080' } },
})
