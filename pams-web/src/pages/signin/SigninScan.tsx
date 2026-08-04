import { useEffect, useMemo, useState } from 'react'
import { Button, Form, Input, InputNumber, Typography, message } from 'antd'
import { useParams } from 'react-router-dom'
import { getScanConfig, scanSignin, type SigninFieldConfigVO } from '@/api/signin'

interface FieldValue {
  key: string
  fieldName: string
  required: boolean
  fieldType: string
  value?: string | number
}

interface ScanFormValues {
  fields: FieldValue[]
}

/** 活动未配置核验字段时的默认表单：姓名（必填）+ 学号（选填） */
const DEFAULT_FIELDS: Array<{ fieldName: string; fieldKey: string; required: boolean; fieldType: string }> = [
  { fieldName: '姓名', fieldKey: 'name', required: true, fieldType: 'TEXT' },
  { fieldName: '学号', fieldKey: 'studentNo', required: false, fieldType: 'TEXT' },
]

/**
 * 免登录扫码签到落地页（/signin/:token）。
 * 复用 login-page / login-card 的毛玻璃样式；扫码链接为未登录的普通成员/同学打开。
 * 加载 scan-config（公开接口）→ 活动配置了核验字段则按配置动态生成表单
 * （fieldName 标签 + required 必填 + 类型 TEXT→Input / NUMBER→InputNumber / PHONE→Input），
 * 未配置则回退默认「姓名+学号」。提交 {token, fields:{字段名:值}} 走 scan 新格式。
 * 无效/过期 token 由后端返回业务错误，http 拦截层统一 message 提示（本页无需额外错误态）。
 */
export default function SigninScan() {
  const { token } = useParams()
  const [done, setDone] = useState(false)
  const [saving, setSaving] = useState(false)
  const [configured, setConfigured] = useState<SigninFieldConfigVO[] | null>(null)
  const [form] = Form.useForm<ScanFormValues>()

  // 是否按配置动态渲染：未加载完成时为 null → 渲染加载态；加载完成且配置了字段 → true
  const useConfigured = configured !== null && configured.length > 0
  const fieldConfigs = useConfigured ? configured : DEFAULT_FIELDS

  useEffect(() => {
    if (!token) return
    let cancelled = false
    getScanConfig(token)
      .then((cfg) => {
        if (cancelled) return
        setConfigured(cfg.fields ?? [])
      })
      .catch(() => {
        if (cancelled) return
        // 无效/过期 token：http 拦截已提示；回退默认表单，提交时后端仍会拦截
        setConfigured([])
      })
    return () => {
      cancelled = true
    }
  }, [token])

  const handleSubmit = async (values: ScanFormValues) => {
    if (!token) return
    setSaving(true)
    try {
      const fields: Record<string, string> = {}
      for (const f of values.fields ?? []) {
        if (f.value !== undefined && f.value !== null && String(f.value).trim() !== '') {
          fields[f.fieldName] = String(f.value).trim()
        }
      }
      await scanSignin({ token, fields })
      setDone(true)
      message.success('签到成功')
    } catch {
      /* http 拦截已提示（无效/过期码） */
    } finally {
      setSaving(false)
    }
  }

  // 按 fieldType 渲染表单项控件（TEXT→Input / NUMBER→InputNumber / PHONE→Input）
  const renderControl = (type: string) => {
    switch (type) {
      case 'NUMBER':
        return <InputNumber style={{ width: '100%' }} max={999999999999} placeholder="请输入" />
      case 'PHONE':
        return <Input maxLength={20} placeholder="请输入手机号" />
      default:
        return <Input maxLength={50} placeholder="请输入" />
    }
  }

  // 动态表单：Form.Item name 用数组路径 ['fields', i, 'value'] 收集输入，提交后 values.fields[i].value
  const formItems = useMemo(
    () =>
      fieldConfigs.map((f, i) => ({
        name: ['fields', i, 'value'],
        label: f.fieldName,
        rules: f.required ? [{ required: true, message: `请输入${f.fieldName}` }] : [],
        control: renderControl(f.fieldType || 'TEXT'),
      })),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [configured, token],
  )

  return (
    <div className="login-page">
      <div className="glass-card login-card">
        <Typography.Title level={3} style={{ textAlign: 'center', color: 'var(--color-text)' }}>
          活动签到
        </Typography.Title>
        <Typography.Paragraph style={{ textAlign: 'center', color: 'var(--color-text-secondary)' }}>
          扫码签到 · 信息与智能工程学院党建办公室
        </Typography.Paragraph>
        {done ? (
          <div style={{ textAlign: 'center' }}>
            <div style={{ fontSize: 22, color: 'var(--color-red)', marginBottom: 8 }}>✓ 签到成功</div>
            <Button onClick={() => window.location.reload()}>继续</Button>
          </div>
        ) : configured === null ? (
          <div style={{ textAlign: 'center', padding: '24px 0' }}>加载中…</div>
        ) : (
          <Form form={form} layout="vertical" onFinish={handleSubmit}>
            {formItems.map((item) => (
              <Form.Item key={item.name[1]} name={item.name} label={item.label} rules={item.rules}>
                {item.control}
              </Form.Item>
            ))}
            <Button type="primary" htmlType="submit" loading={saving} block>
              签到
            </Button>
          </Form>
        )}
      </div>
    </div>
  )
}
