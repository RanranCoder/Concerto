import type { CSSProperties, ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ConfigProvider } from 'antd'
import BrandIdentity from '@/components/BrandIdentity'
import { DEFAULT_PRIMARY_COLOR } from '@/stores/org'
import { resolveTokens } from '@/styles/tokens'
import { useThemeStore } from '@/stores/theme'
import { readableOn } from '@/styles/color'
import '@/styles/auth.css'

export default function AuthLayout({ children, aside, brand = DEFAULT_PRIMARY_COLOR, register = false, brandVariant = 'auto', floating }: {
  children: ReactNode
  aside: ReactNode
  brand?: string
  register?: boolean
  brandVariant?: 'auto' | 'blue'
  /** 固定悬浮内容（如演示账号列表）：不参与主网格布局。 */
  floating?: ReactNode
}) {
  const mode = useThemeStore((state) => state.mode)
  const colors = resolveTokens(mode, brand)
  const onBrand = readableOn(colors.onBrand, brand)
  return (
    <ConfigProvider theme={{ token: { colorPrimary: brand, colorPrimaryText: colors.brandText, colorTextLightSolid: onBrand } }} button={{ autoInsertSpace: false }}>
      <div className={`onboarding${register ? ' onboarding-register' : ''}`} style={{ '--auth-brand': brand, '--auth-brand-hover': colors.brandHover, '--auth-on-brand': onBrand, '--brand-text': colors.brandText } as CSSProperties}>
        <header className="onboarding-header">
          <Link to="/login" aria-label="协奏登录"><BrandIdentity variant={brandVariant} /></Link>
          <div className="onboarding-header-link">
            <span>{register ? '已有账号？' : '为你的团队开启协作'}</span>
            <Link to={register ? '/login' : '/register'}>{register ? '返回登录' : '创建组织'}</Link>
          </div>
        </header>
        <main className="onboarding-body">
          <aside className="onboarding-aside">{aside}</aside>
          <section className="onboarding-content">{children}</section>
        </main>
        {floating}
        <footer className="onboarding-footer">协奏 Concerto <span>让学生组织的每一份努力，都有回响。</span></footer>
      </div>
    </ConfigProvider>
  )
}
