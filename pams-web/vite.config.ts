import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'
import { isSameOriginDevRequest } from './dev/proxyOrigin'

const apiTarget = 'http://localhost:8080'

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
      '/api': {
        target: apiTarget,
        changeOrigin: true,
        configure(proxy) {
          proxy.on('proxyReq', (proxyReq, request) => {
            // Host is rewritten by changeOrigin; keep same-origin browser requests
            // same-origin upstream, without normalizing requests from other sites.
            if (isSameOriginDevRequest(request.headers.origin, request.headers.host)) {
              proxyReq.setHeader('Origin', new URL(apiTarget).origin)
            }
          })
        },
      },
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
