/** 灵思头像图片资源（放在 public/lingsi/ 下）。原图等比缩放，不做裁切。 */
/** 大尺寸展示位 */
export const LINGSI_AVATAR_SRC = '/lingsi/ranran-avatar.jpg'
/** 同一张原图的更小尺寸版本，用于小尺寸头像以减少流量 */
export const LINGSI_AVATAR_SRC_SM = '/lingsi/ranran-avatar-sm.jpg'

/** 按展示尺寸选择图片版本：小尺寸用缩略图 */
function lingsiAvatarSrc(size: number): string {
  return size <= 32 ? LINGSI_AVATAR_SRC_SM : LINGSI_AVATAR_SRC
}

interface Props {
  size?: number
}

/** 灵思头像：人物形象图片（设计稿 §9） */
export default function LingsiAvatar({ size = 30 }: Props) {
  return (
    <span
      className="lingsi-avatar"
      style={{ width: size, height: size, borderRadius: size * 0.33 }}
      aria-hidden
    >
      <img src={lingsiAvatarSrc(size)} alt="" className="lingsi-avatar-img" />
    </span>
  )
}
