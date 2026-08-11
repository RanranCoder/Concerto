import { useEffect, useState } from 'react'
import { App, Button, Checkbox, Form, Space } from 'antd'
import { SettingOutlined } from '@ant-design/icons'
import GlassModal from '@/components/glass/GlassModal'
import { getRosterHeaders, saveSigninFields, getSigninFields } from '@/api/signin'

/**
 * 核验字段配置面板（GlassModal）。
 * 打开时异步加载当前活动的应签名单表头字段，用户可勾选需要核验的字段。
 * 勾选的字段在扫码签到时为必填项。
 */
export default function SigninFieldConfig({
  activityId,
  onChanged,
}: {
  activityId: number
  /** 保存成功回调，父级刷新 */
  onChanged?: () => void
}) {
  const { message } = App.useApp()
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(false)
  const [headers, setHeaders] = useState<string[]>([])
  const [form] = Form.useForm()

  // 打开弹窗时加载名单表头字段和已配置的核验字段
  useEffect(() => {
    if (!open) return
    let cancelled = false
    setLoading(true)
    Promise.all([getRosterHeaders(activityId), getSigninFields(activityId)])
      .then(([rosterHeaders, fieldConfigs]) => {
        if (cancelled) return
        setHeaders(rosterHeaders ?? [])
        // 已配置的核验字段名列表
        const configuredFields = (fieldConfigs ?? []).map((f) => f.fieldName)
        form.setFieldsValue({ fields: configuredFields })
      })
      .catch(() => {
        /* http 拦截已提示 */
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [open, activityId, form])

  const handleSave = async () => {
    const values = await form.validateFields()
    const selectedFields: string[] = values.fields ?? []
    setSaving(true)
    try {
      await saveSigninFields(
        activityId,
        selectedFields.map((fieldName) => ({
          fieldName,
          fieldKey: fieldName,
          required: true,
          fieldType: 'TEXT',
        })),
      )
      message.success('核验字段已保存')
      setOpen(false)
      onChanged?.()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <Button icon={<SettingOutlined />} onClick={() => setOpen(true)}>
        核验字段配置
      </Button>
      <GlassModal
        title="核验字段配置"
        open={open}
        onCancel={() => setOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        }
      >
        <div style={{ marginBottom: 12, fontSize: 12, color: 'var(--color-text-secondary)' }}>
          选择需要核验的字段，勾选的字段在扫码签到时为必填项。
        </div>
        {loading ? (
          <div style={{ textAlign: 'center', color: 'var(--color-text-secondary)' }}>加载中…</div>
        ) : headers.length === 0 ? (
          <div style={{ textAlign: 'center', color: 'var(--color-text-secondary)' }}>请先上传应签名单</div>
        ) : (
          <Form form={form} layout="vertical">
            <Form.Item name="fields" style={{ marginBottom: 0 }}>
              <Checkbox.Group style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {headers.map((header) => (
                  <Checkbox key={header} value={header}>
                    {header}
                  </Checkbox>
                ))}
              </Checkbox.Group>
            </Form.Item>
          </Form>
        )}
      </GlassModal>
    </>
  )
}