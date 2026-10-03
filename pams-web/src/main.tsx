import React from 'react'
import ReactDOM from 'react-dom/client'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import App from '@/App'
import { installTokenCss } from '@/styles/tokens'
import '@/styles/global.css'
import '@/styles/glass.css'
import '@/styles/tokens.css'
import '@/styles/lingsi.css'

// 全局 dayjs 中文 locale：format('ddd') 等输出中文星期（Task 28 minor）
dayjs.locale('zh-cn')

// CSS 变量由 tokens.ts 生成注入：与 antd theme 同源，避免「一套值抄两份」（设计方案 §4.1）
installTokenCss()

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
