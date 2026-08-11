import { useEffect, useState } from 'react'
import { ConfigProvider, App as AntApp } from 'antd'
import zhCN from 'antd/locale/zh_CN'
import { RouterProvider } from 'react-router-dom'
import { useThemeStore, getAntdTheme } from '@/stores/theme'
import { router } from '@/router'
import { FeedbackBridge } from '@/utils/feedback'

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
          colorInfo: '#2b6cb8',
          colorLink: '#DE2910',
          colorBgContainer: mode === 'dark' ? '#1b1e26' : '#f7f8fa',
          colorBgElevated: mode === 'dark' ? '#23262f' : '#fbfcfe',
          colorBorder: mode === 'dark' ? '#2e313a' : '#e4e8ef',
          borderRadius: 12,
          controlHeight: 30,
          fontFamily: "'PingFang SC','Microsoft YaHei','HarmonyOS Sans SC','Noto Sans SC',sans-serif",
        },
        components: {
          Menu: {
            // 透明侧边栏上选中项高亮：用主题自适应红系 token，避免 antd 默认选中背景
            // （hashed:false 下固定 #ffede6）在 dark 主题下退化成浅粉白块
            itemSelectedBg: mode === 'dark' ? 'rgba(222,41,16,0.28)' : 'rgba(222,41,16,0.14)',
            itemSelectedColor: '#DE2910',
            itemColor: mode === 'dark' ? 'rgba(255,255,255,0.85)' : 'rgba(17,24,39,0.92)',
          },
          Button: {
            fontWeight: 500,
          },
          Form: {
            itemMarginBottom: 16,
            labelColor: mode === 'dark' ? 'rgba(240,244,250,0.55)' : 'rgba(17,24,39,0.6)',
          },
          Select: {
            optionSelectedFontWeight: 600,
            optionSelectedBg: 'rgba(222,41,16,0.14)',
          },
          Input: {
            activeShadow: '0 0 0 3px rgba(222,41,16,0.12)',
          },
        },
      }}
    >
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
