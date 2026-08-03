import { useCallback, useEffect, useRef, useState } from 'react'
import { Button, Tag } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { QRCodeSVG } from 'qrcode.react'
import { countSignins, generateSigninToken } from '@/api/signin'

interface SigninQRProps {
  activityId: number
  onSigned: () => void
  /** 所在 Tab 是否激活：非激活时停止轮询，避免「签到」页不在前台仍常驻轮询 */
  active?: boolean
}

/**
 * 扫码签到二维码卡片。
 *
 * - qrContent 由后端按请求 origin 拼好（部署在反向代理后时取 X-Forwarded-Host），
 *   但 dev 下 vite proxy changeOrigin 会把 Host 重写为 localhost:8080，后端拼出的
 *   qrContent 指向后端端口（不托管 SPA）会 404。因此这里统一用前端自身的
 *   window.location.origin 覆盖，保证扫码链接落到本机前端路由 /signin/{token}。
 * - “未刷新长期有效”：后端刷新即作废旧令牌（新码生效、旧码作废），未刷新则 24h 内
 *   一直有效。扫码在他人设备上发生，本组件轮询签到人数，人数增长时回调 onSigned
 *   触发列表自动刷新，出现 SCAN 记录。
 */
export default function SigninQR({ activityId, onSigned, active = true }: SigninQRProps) {
  const [qrContent, setQrContent] = useState('')
  const [expiresAt, setExpiresAt] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const lastCountRef = useRef(0)

  const refresh = useCallback(async () => {
    setLoading(true)
    try {
      const t = await generateSigninToken(activityId)
      setQrContent(`${window.location.origin}/signin/${t.token}`)
      setExpiresAt(t.expiresAt)
      lastCountRef.current = (await countSignins(activityId)) ?? 0
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [activityId])

  useEffect(() => {
    refresh()
  }, [refresh])

  // 轮询签到人数：数量增长说明有新的扫码签到，触发列表自动刷新。
  // 仅当「签到」Tab 处于激活状态时轮询（active=false 清除定时器，切走即停）。
  useEffect(() => {
    if (!active) return
    const timer = setInterval(async () => {
      try {
        const c = (await countSignins(activityId)) ?? 0
        if (c > lastCountRef.current) {
          lastCountRef.current = c
          onSigned()
        }
      } catch {
        /* 轮询失败静默，下个周期重试 */
      }
    }, 12000)
    return () => clearInterval(timer)
  }, [activityId, onSigned, active])

  return (
    <div style={{ textAlign: 'center', padding: 16 }}>
      {qrContent ? (
        <QRCodeSVG value={qrContent} size={180} level="M" bgColor="#ffffff" fgColor="#000000" />
      ) : (
        <div style={{ height: 180 }} />
      )}
      <div style={{ marginTop: 8, fontSize: 12, color: 'var(--color-text-secondary)' }}>
        扫码签到 · 有效期至 {expiresAt ? new Date(expiresAt).toLocaleTimeString() : '-'}
        <Tag style={{ marginLeft: 8 }} color="green">
          未刷新长期有效
        </Tag>
      </div>
      <Button icon={<ReloadOutlined />} loading={loading} onClick={refresh} style={{ marginTop: 8 }}>
        刷新二维码
      </Button>
    </div>
  )
}
