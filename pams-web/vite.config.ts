import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  define: {
    global: 'globalThis',
  },
  server: {
    port: 5173,
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: true },
      '/uploads': { target: 'http://localhost:8080', changeOrigin: true },
      '/ws': { target: 'http://localhost:8080', ws: true },
    },
  },
  test: {
    environment: 'node',
    globals: true,
    // e2e 目录是 Playwright 用例（npm run test:e2e），不能被 vitest 收集：
    // 两者 test()/describe() 签名不兼容，混跑会直接报 "did not expect test.describe() to be called here"。
    exclude: ['**/node_modules/**', '**/dist/**', 'e2e/**'],
  },
})
