import { Button } from 'antd'
import { MoonOutlined, SunOutlined } from '@ant-design/icons'
import { useThemeStore } from '@/stores/theme'

export default function ThemeSwitch() {
  const mode = useThemeStore((s) => s.mode)
  const toggle = useThemeStore((s) => s.toggle)

  return (
    <Button
      type="text"
      shape="circle"
      aria-label="切换主题"
      icon={mode === 'dark' ? <SunOutlined /> : <MoonOutlined />}
      onClick={toggle}
    />
  )
}
