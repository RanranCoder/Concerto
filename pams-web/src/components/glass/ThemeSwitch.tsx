import { Button, Tooltip } from 'antd'
import { MoonOutlined, SunOutlined } from '@ant-design/icons'
import { useThemeStore } from '@/stores/theme'

export default function ThemeSwitch() {
  const mode = useThemeStore((s) => s.mode)
  const toggle = useThemeStore((s) => s.toggle)
  return (
    <Tooltip title={mode === 'dark' ? '切换深色' : '切换暗色'}>
      <Button
        type="text"
        shape="circle"
        icon={mode === 'dark' ? <SunOutlined /> : <MoonOutlined />}
        onClick={toggle}
        style={{ color: 'var(--color-text)' }}
      />
    </Tooltip>
  )
}
