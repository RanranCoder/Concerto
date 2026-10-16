import '@/styles/brand.css'

/** 产品标识与组织标识分开：组织主题可以换色，产品名称保持一致。 */
export default function BrandIdentity({ compact = false }: { compact?: boolean }) {
  return (
    <div className={`product-brand${compact ? ' product-brand-compact' : ''}`}>
      <span className="product-brand-symbol" aria-hidden="true">
        <svg viewBox="0 0 40 40" fill="none" focusable="false">
          <path d="M29 11a12 12 0 1 0 0 18" stroke="currentColor" strokeWidth="3" strokeLinecap="round" />
          <path d="M18 14h7l5 6M16 20h14M18 26h7l5-6" stroke="currentColor" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
          <circle cx="31" cy="20" r="2.5" fill="currentColor" />
        </svg>
      </span>
      <div className="product-brand-copy">
        <div className="product-brand-wordmark">
          <strong className="product-brand-name">协奏</strong>
          <span className="product-brand-english" lang="en">Concerto</span>
        </div>
        <span className="product-brand-description">学生组织智能工作台</span>
      </div>
    </div>
  )
}
