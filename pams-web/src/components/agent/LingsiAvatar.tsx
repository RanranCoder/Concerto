import { Sparkles } from './LingsiIcons'

interface Props {
  size?: number
  /** 用户侧的头像用另一种色（暂时不用，留接口） */
  variant?: 'agent' | 'user'
}

/** 灵思头像：红色系方圆形 + 星火图标（设计稿 §9） */
export default function LingsiAvatar({ size = 30 }: Props) {
  return (
    <span
      className="lingsi-avatar"
      style={{ width: size, height: size, borderRadius: size * 0.33, fontSize: size * 0.5 }}
      aria-hidden
    >
      <Sparkles />
    </span>
  )
}
