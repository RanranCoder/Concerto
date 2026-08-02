import React from 'react'
import ReactDOM from 'react-dom/client'
import dayjs from 'dayjs'
import 'dayjs/locale/zh-cn'
import App from '@/App'
import '@/styles/global.css'
import '@/styles/glass.css'
import '@/styles/tokens.css'

// 全局 dayjs 中文 locale：format('ddd') 等输出中文星期（Task 28 minor）
dayjs.locale('zh-cn')

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
)
