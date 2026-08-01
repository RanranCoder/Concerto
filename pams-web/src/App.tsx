import { useEffect, useState } from 'react'
import { ConfigProvider, App as AntApp } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { RouterProvider } from 'react-router-dom'
import { useThemeStore, getAntdTheme } from '@/stores/theme'
import { router } from '@/router'

function ThemeConfigProvider() {
  const [mode, setMode] = useState(useThemeStore.getState().mode)

  useEffect(() => {
    // persist 在 store 创建时同步 rehydrate 了 mode，但 data-theme 只在 setMode 里写入，
    // 这里在首次挂载时补齐，保证 CSS 变量与持久化主题一致。
    document.documentElement.dataset.theme = useThemeStore.getState().mode
    const unsub = useThemeStore.subscribe((state) => setMode(state.mode))
    return unsub
  }, [])

  const antd = getAntdTheme(mode)

  return (
    <ConfigProvider
      locale={zhCN}
      theme={{
        algorithm: antd.algorithm,
        cssVar: true,
        hashed: false,
        token: {
          colorPrimary: '#DE2910',
          colorBgContainer: mode === 'dark' ? 'rgba(255,255,255,0.06)' : 'rgba(255,255,255,0.55)',
          colorBorder: mode === 'dark' ? 'rgba(255,255,255,0.14)' : 'rgba(0,0,0,0.10)',
          borderRadius: 12,
          fontFamily: "'PingFang SC','Microsoft YaHei','HarmonyOS Sans SC','Noto Sans SC',sans-serif",
        },
      }}
    >
      <AntApp>
        <RouterProvider router={router} />
      </AntApp>
    </ConfigProvider>
  )
}

export default function App() {
  return <ThemeConfigProvider />
}
