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
          colorBgContainer: mode === 'dark' ? '#1b1e26' : '#f7f8fa',
          colorBgElevated: mode === 'dark' ? '#23262f' : '#fbfcfe',
          colorBorder: mode === 'dark' ? '#2e313a' : '#e4e8ef',
          borderRadius: 12,
          fontFamily: "'PingFang SC','Microsoft YaHei','HarmonyOS Sans SC','Noto Sans SC',sans-serif",
        },
        components: {
          Menu: {
            // 玻璃 Sider 上选中项高亮：用主题自适应红系 token，避免 antd 默认选中背景
            // （hashed:false 下固定 #ffede6）在 dark 玻璃上退化成浅粉白块
            itemSelectedBg: mode === 'dark' ? 'rgba(222,41,16,0.28)' : 'rgba(222,41,16,0.14)',
            itemSelectedColor: '#DE2910',
            itemColor: mode === 'dark' ? 'rgba(255,255,255,0.85)' : 'rgba(17,24,39,0.92)',
          },
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
