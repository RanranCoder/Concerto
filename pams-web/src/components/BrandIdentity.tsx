import { useThemeStore } from '@/stores/theme'
import '@/styles/brand.css'

/** V1.7 产品标识固定使用品牌色，组织主题只影响业务界面。 */
export default function BrandIdentity({ compact = false, variant = 'auto' }: { compact?: boolean; variant?: 'auto' | 'blue' }) {
  const mode = useThemeStore((state) => state.mode)
  return (
    <div className={`product-brand${compact ? ' product-brand-compact' : ''}${variant === 'blue' ? ' product-brand-blue' : ''}`}>
      <img className="product-brand-symbol" src={`/brand/concerto-symbol${variant === 'blue' || mode === 'dark' ? '-dark' : ''}.svg`}
        width={64} height={64} alt="" aria-hidden="true" />
      <div className="product-brand-copy">
        <div className="product-brand-wordmark">
          <span className="product-brand-english" lang="en">Concerto</span>
          <span className="product-brand-separator" aria-hidden="true">·</span>
          <strong className="product-brand-name">协奏</strong>
        </div>
        {!compact && <span className="product-brand-description">高校学生组织智能工作台</span>}
      </div>
    </div>
  )
}
