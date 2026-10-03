import { useEffect, useState } from 'react'
import { ConfigProvider, App as AntApp } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { RouterProvider } from 'react-router-dom'
import { useThemeStore } from '@/stores/theme'
import { useOrgStore } from '@/stores/org'
import { buildAntdTheme } from '@/styles/theme'
import { installTokenCss } from '@/styles/tokens'
import { router } from '@/router'
import { FeedbackBridge } from '@/utils/feedback'

function ThemeConfigProvider() {
  const [mode, setMode] = useState(useThemeStore.getState().mode)
  // 主题主色由当前组织的 theme_json 驱动（stores/org.ts），未配置时回退院校主蓝
  const primary = useOrgStore((s) => s.primaryColor)

  useEffect(() => {
    // persist 在 store 创建时同步 rehydrate 了 mode，但 data-theme 只在 setMode 里写入，
    // 这里在首次挂载时补齐，保证 CSS 变量与持久化主题一致。
    document.documentElement.dataset.theme = useThemeStore.getState().mode
    const unsub = useThemeStore.subscribe((state) => setMode(state.mode))
    return unsub
  }, [])

  // CSS 变量与 antd theme 同源重建：主色/明暗变化都能同时生效，
  // 不会出现「antd 组件换了色、自定义 CSS 还是旧色」。见设计方案 §4.1
  useEffect(() => {
    installTokenCss(primary)
  }, [primary])

  return (
    <ConfigProvider locale={zhCN} theme={buildAntdTheme(mode, primary)}>
      <AntApp>
        <FeedbackBridge />
        <RouterProvider router={router} />
      </AntApp>
    </ConfigProvider>
  )
}

export default function App() {
  return <ThemeConfigProvider />
}
