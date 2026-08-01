import GlassCard from '@/components/glass/GlassCard'

// 建设中占位组件：未实现的页面统一返回，保证路由可访问不白屏
export default function Building({ module }: { module: string }) {
  return (
    <GlassCard style={{ padding: 40, textAlign: 'center' }}>
      <h3 style={{ color: 'var(--color-text)', marginBottom: 8 }}>{module}</h3>
      <p style={{ color: 'var(--color-text-secondary)' }}>建设中，敬请期待。</p>
    </GlassCard>
  )
}
