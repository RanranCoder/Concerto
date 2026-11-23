import { useCallback, useEffect, useRef, useState } from 'react'
import { Alert, Button, Space } from 'antd'
import { ReloadOutlined } from '@ant-design/icons'
import { QRCodeSVG } from 'qrcode.react'
import {
  closeActivitySignin,
  countSignins,
  generateActivitySigninToken,
  getActivitySigninToken,
  signinTokenExpiresAt,
  type ActivitySigninToken,
} from '@/api/signin'

interface SigninQRProps {
  activityId: number
  onSigned: () => void
  /** 所在 Tab 是否激活：非激活时停止轮询，避免「签到」页不在前台仍常驻轮询 */
  active?: boolean
}

export default function SigninQR({ activityId, onSigned, active = true }: SigninQRProps) {
  const [qr, setQr] = useState<ActivitySigninToken | null>(null)
  const [loading, setLoading] = useState(true)
  const [failed, setFailed] = useState(false)
  const lastCountRef = useRef(0)
  const requestVersion = useRef(0)

  const refresh = useCallback(async () => {
    const version = ++requestVersion.current
    try {
      const current = await getActivitySigninToken(activityId)
      if (version === requestVersion.current) {
        setQr(current)
        setFailed(false)
      }
    } catch {
      if (version === requestVersion.current) setFailed(true)
    } finally {
      if (version === requestVersion.current) setLoading(false)
    }
  }, [activityId])

  useEffect(() => {
    if (!active) return
    setLoading(true)
    setQr(null)
    void refresh()
    const timer = window.setInterval(() => { if (!document.hidden) void refresh() }, 12_000)
    return () => {
      ++requestVersion.current
      window.clearInterval(timer)
    }
  }, [active, refresh])

  useEffect(() => {
    if (!qr) return
    const timer = window.setTimeout(() => void refresh(),
      Math.max(0, signinTokenExpiresAt(qr.expiresAt) - Date.now()))
    return () => window.clearTimeout(timer)
  }, [qr, refresh])

  const changeToken = async (close = false) => {
    ++requestVersion.current
    setLoading(true)
    try {
      if (close) {
        await closeActivitySignin(activityId)
        setQr(null)
      } else {
        setQr(await generateActivitySigninToken(activityId))
      }
      setFailed(false)
    } catch {
      setFailed(true)
    } finally {
      ++requestVersion.current
      setLoading(false)
    }
  }

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
      {failed && <Alert type="error" message="签到状态读取或更新失败"
        action={<Button size="small" onClick={() => void refresh()}>重试</Button>} />}
      {qr ? (
        <>
          <QRCodeSVG value={window.location.origin + '/signin/' + qr.token}
            size={180} level="M" bgColor="#ffffff" fgColor="#000000" />
          <p>有效期至 {qr.expiresAt.replace('T', ' ').slice(0, 16)}</p>
        </>
      ) : (
        !loading && <p>签到尚未开启</p>
      )}
      <Space wrap style={{ marginTop: 8 }}>
        <Button icon={<ReloadOutlined />} loading={loading} onClick={() => void changeToken()}>
          {qr ? '刷新二维码' : '开启签到'}
        </Button>
        {qr && <Button danger disabled={loading} onClick={() => void changeToken(true)}>关闭签到</Button>}
      </Space>
    </div>
  )
}
