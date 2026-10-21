/** 顶栏品牌点缀：装饰五线谱不参与导航交互及无障碍阅读。 */
export default function HeaderStaff() {
  return (
    <svg className="app-header-staff" viewBox="0 0 960 56" preserveAspectRatio="xMidYMid slice" aria-hidden="true" focusable="false">
      <defs>
        <linearGradient id="header-staff-ink" x1="0" y1="0" x2="1" y2="0">
          <stop offset="0" stopColor="currentColor" stopOpacity="0" />
          <stop offset=".35" stopColor="currentColor" />
          <stop offset=".7" stopColor="currentColor" />
          <stop offset="1" stopColor="currentColor" stopOpacity="0" />
        </linearGradient>
      </defs>
      <g fill="none" stroke="url(#header-staff-ink)" strokeWidth="1" strokeLinecap="round">
        {[0, 6, 12, 18, 24].map((offset) => (
          <path key={offset} d={`M0 ${25 + offset}H390C460 ${25 + offset} 475 ${6 + offset} 550 ${6 + offset}H960`} vectorEffect="non-scaling-stroke" />
        ))}
      </g>
      <g fill="currentColor">
        <circle cx="220" cy="43" r="2.5" />
        <circle cx="355" cy="37" r="3" />
        <circle cx="695" cy="12" r="3.5" />
        <circle cx="840" cy="24" r="4.5" />
      </g>
      <circle cx="615" cy="18" r="3.5" fill="#C7F36B" />
    </svg>
  )
}
