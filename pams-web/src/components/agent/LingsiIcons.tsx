/**
 * 灵思自绘图标集。
 *
 * 为什么不用 @ant-design/icons：灵思的「过程区」图标需要成组出现且语义特定
 * （思考脉冲 / 工具执行 / 计划 / 中止），antd 里对应的现成图标语义不够贴合，
 * 混用会造成视觉不统一。这里统一用 1.2px 线宽的极简线性图标，颜色继承 currentColor。
 */

interface IconProps {
  size?: number
}

const base = (size: number) => ({
  width: size,
  height: size,
  viewBox: '0 0 16 16',
  fill: 'none',
  stroke: 'currentColor',
  strokeWidth: 1.3,
  strokeLinecap: 'round' as const,
  strokeLinejoin: 'round' as const,
  style: { display: 'inline-block' as const, verticalAlign: '-0.125em' },
})

/** 星火（品牌标识） */
export function Sparkles({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M8 1.8l1.5 4.2 4.2 1.5-4.2 1.5L8 13.2 6.5 9 2.3 7.5 6.5 6z" />
      <path d="M12.6 1.4l.5 1.4 1.4.5-1.4.5-.5 1.4-.5-1.4-1.4-.5 1.4-.5z" />
    </svg>
  )
}

/**
 * 资料 / 知识库（对话档位）。
 *
 * 刻意**不用放大镜**：放大镜在本系统里已表示"搜索材料"（Search），
 * 复用会让「切到对话档位」看起来像"打开搜索框"。书本的语义是"查资料"，
 * 与档位的实际含义一致 —— 这是它与「只读/标准」的视觉分组手段之一。
 */
export function Book({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M2.6 2.8h4.2c.6 0 1.2.5 1.2 1.2v9.2c0-.6-.5-1.1-1.2-1.1H2.6z" />
      <path d="M13.4 2.8H9.2c-.6 0-1.2.5-1.2 1.2v9.2c0-.6.5-1.1 1.2-1.1h4.2z" />
    </svg>
  )
}

/** 思考中（旋转弧） */
export function Thinking({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M8 2.2a5.8 5.8 0 105.8 5.8" opacity="0.9" />
      <circle cx="8" cy="8" r="1.4" fill="currentColor" stroke="none" />
    </svg>
  )
}

/** 工具调用 */
export function Tool({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M10.4 2.2a3.2 3.2 0 00-3.9 4.1L2.6 10.2a1.4 1.4 0 002 2l3.9-3.9a3.2 3.2 0 004.1-3.9l-2 2-1.8-.5-.5-1.8z" />
    </svg>
  )
}

/** 完成 */
export function Check({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M3.2 8.4l3 3 6.6-6.8" />
    </svg>
  )
}

/** 失败 */
export function Cross({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M4 4l8 8M12 4l-8 8" />
    </svg>
  )
}

/** 拟执行清单 */
export function Plan({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <rect x="2.6" y="2" width="10.8" height="12" rx="1.6" />
      <path d="M5.6 5.8h4.8M5.6 8.4h4.8M5.6 11h3" />
    </svg>
  )
}

/** 已中止 */
export function Aborted({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <circle cx="8" cy="8" r="6" />
      <path d="M5.6 5.6l4.8 4.8" />
    </svg>
  )
}

/** 引用 / 跳转 */
export function ArrowRight({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M3 8h9M8.4 4.6L11.8 8l-3.4 3.4" />
    </svg>
  )
}

/** 展开 / 折叠箭头 */
export function Caret({ size = 16, open = false }: IconProps & { open?: boolean }) {
  return (
    <svg
      {...base(size)}
      style={{ ...base(size).style, transform: open ? 'rotate(90deg)' : 'none', transition: 'transform .18s ease' }}
      aria-hidden
    >
      <path d="M6 3.5L10.5 8 6 12.5" />
    </svg>
  )
}

/** 发送 */
export function Send({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M14 2L7 9M14 2l-4.6 12-2.4-5-5-2.4z" />
    </svg>
  )
}

/** 向上发送 */
export function ArrowUp({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M8 13V3M3.8 7.2L8 3l4.2 4.2" />
    </svg>
  )
}

/** 停止 */
export function Stop({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <rect x="4.4" y="4.4" width="7.2" height="7.2" rx="1.4" />
    </svg>
  )
}

/** 警告（跳过项 / 不可逆提示） */
export function Warn({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M8 2.2l6 10.4H2z" />
      <path d="M8 6.4v3.1M8 11.2v.1" />
    </svg>
  )
}

/** 规则模式（降级标识） */
export function Rules({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M3 4.2h10M3 8h10M3 11.8h6" />
      <circle cx="12" cy="11.8" r="1.5" />
    </svg>
  )
}

/** 能力清单 / 工具箱 */
export function Toolbox({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <rect x="2" y="5.4" width="12" height="8" rx="1.6" />
      <path d="M6 5.4V3.8A1.2 1.2 0 017.2 2.6h1.6A1.2 1.2 0 0110 3.8v1.6" />
      <path d="M2 8.6h12" />
    </svg>
  )
}

/* ============================================================================
 * UI v2 新增（参考 Trae Work 的设计语言）
 * ========================================================================== */

/** 复制 */
export function Copy({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <rect x="5.6" y="5.6" width="7.8" height="7.8" rx="1.6" />
      <path d="M10.4 5.6V3.8a1 1 0 00-1-1H3.8a1 1 0 00-1 1v5.6a1 1 0 001 1h1.8" />
    </svg>
  )
}

/** 有帮助（点赞） */
export function ThumbUp({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M5 7.6l2.6 4.8c.5.9 1.9.5 1.9-.5V10h2.4c.9 0 1.5-.8 1.3-1.6l-.8-3.2A1.4 1.4 0 0011 4.2H7.6" />
      <path d="M5 7.6V12M5 7.6H3.2a.7.7 0 00-.7.7v3.1c0 .4.3.7.7.7H5" />
    </svg>
  )
}

/** 重新生成 */
export function Reload({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M13 8a5 5 0 11-1.6-3.7" />
      <path d="M13 2.6V5h-2.4" />
    </svg>
  )
}

/** 只读模式（锁） */
export function Lock({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <rect x="3.4" y="7" width="9.2" height="6.2" rx="1.6" />
      <path d="M5.8 7V5.2a2.2 2.2 0 014.4 0V7" />
    </svg>
  )
}

/** 关闭 */
export function Close({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M4.2 4.2l7.6 7.6M11.8 4.2l-7.6 7.6" />
    </svg>
  )
}

/** 新增 / 添加 */
export function Plus({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M8 3.4v9.2M3.4 8h9.2" />
    </svg>
  )
}

/** 在右侧面板打开（↗） */
export function OpenExternal({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M6.6 3.6h5.8v5.8" />
      <path d="M12.4 3.6L5 11" />
    </svg>
  )
}

/** 右侧分屏面板开关 */
export function PanelRight({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <rect x="2.2" y="3.2" width="11.6" height="9.6" rx="1.8" />
      <path d="M9.8 3.2v9.6" />
    </svg>
  )
}

/** 编辑（消息引用块的「编辑并重新发送」） */
export function Pencil({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M10.8 2.8l2.4 2.4-8 8-3 .6.6-3z" />
    </svg>
  )
}

/** 产物（落库产生的实体变更） */
export function Artifact({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M3.2 6.6l4.8-2.4 4.8 2.4-4.8 2.4z" />
      <path d="M3.2 10.2l4.8 2.4 4.8-2.4" />
    </svg>
  )
}

/** 搜索 */
export function Search({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <circle cx="7.2" cy="7.2" r="4.2" />
      <path d="M10.4 10.4L13.4 13.4" />
    </svg>
  )
}

/** 菜单（窄屏打开任务栏抽屉） */
export function Menu({ size = 16 }: IconProps) {
  return (
    <svg {...base(size)} aria-hidden>
      <path d="M3 4.6h10M3 8h10M3 11.4h10" />
    </svg>
  )
}
