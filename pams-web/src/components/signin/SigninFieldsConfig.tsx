import FormSurface from '@/components/form/FormSurface'
import { useEffect, useState } from 'react'
import { App, Button, Checkbox, Form, Input, Select, Space } from 'antd'
import {
  MinusCircleOutlined,
  PlusOutlined,
  SettingOutlined,
} from '@ant-design/icons'
import { getSigninFields, saveSigninFields } from '@/api/signin'

const FIELD_TYPE_OPTIONS = [
  { value: 'TEXT', label: '文本' },
  { value: 'NUMBER', label: '数字' },
  { value: 'PHONE', label: '手机号' },
]

interface FieldRow {
  fieldName: string
  required: boolean
  fieldType: string
}

/**
 * 签到表单字段配置：允许添加/删除任意自定义字段（字段名 + 类型 + 是否必填）。
 * 扫码落地页按此动态渲染表单，提交后字段值回显到签到记录。
 */
export default function SigninFieldsConfig({
  activityId,
}: {
  activityId: number
}) {
  const { message } = App.useApp()
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<{ fields: FieldRow[] }>()

  useEffect(() => {
    if (!open) return
    let cancelled = false
    getSigninFields(activityId)
      .then((list) => {
        if (cancelled) return
        const rows: FieldRow[] = (list ?? []).map((f) => ({
          fieldName: f.fieldName,
          required: f.required,
          fieldType: f.fieldType || 'TEXT',
        }))
        // 姓名/学号为系统基础字段，默认纳入表单（姓名必填）
        const hasName = rows.some((r) => r.fieldName === '姓名')
        const hasNo = rows.some((r) => r.fieldName === '学号')
        if (!hasName)
          rows.unshift({ fieldName: '姓名', required: true, fieldType: 'TEXT' })
        if (!hasNo)
          rows.unshift({
            fieldName: '学号',
            required: false,
            fieldType: 'TEXT',
          })
        form.setFieldsValue({ fields: rows })
      })
      .catch(() => {})
    return () => {
      cancelled = true
    }
  }, [open, activityId, form])

  const handleSave = async () => {
    const values = await form.validateFields()
    const rows = (values.fields ?? []).filter(
      (f) => f.fieldName && f.fieldName.trim(),
    )
    setSaving(true)
    try {
      await saveSigninFields(
        activityId,
        rows.map((f) => ({
          fieldName: f.fieldName.trim(),
          fieldKey: f.fieldName.trim(),
          required: f.required,
          fieldType: f.fieldType || 'TEXT',
        })),
      )
      message.success('签到表单已保存')
      setOpen(false)
    } catch {
      /* http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  return (
    <>
      <Button
        size="small"
        icon={<SettingOutlined />}
        onClick={() => setOpen(true)}
      >
        签到字段设置
      </Button>
      <FormSurface
        mode="drawer"
        description="配置签到时需要收集的字段及必填要求。"
        title="签到表单字段设置"
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
        <div
          style={{
            marginBottom: 12,
            fontSize: 13,
            color: 'var(--text-secondary)',
          }}
        >
          定义扫码时要求填写的字段（姓名/学号为基础字段）。签到完成后这些字段值会回显到「签到记录」列表。
        </div>
        <Form form={form} layout="vertical" preserve={false}>
          <Form.List name="fields">
            {(fields, { add, remove }) => (
              <>
                {fields.map((field) => (
                  <Space
                    key={field.key}
                    align="baseline"
                    style={{ display: 'flex', marginBottom: 8 }}
                  >
                    <Form.Item
                      name={[field.name, 'fieldName']}
                      rules={[{ required: true, message: '请输入字段名' }]}
                      style={{ marginBottom: 0 }}
                    >
                      <Input
                        placeholder="字段名，如 联系电话"
                        style={{ width: 150 }}
                      />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, 'fieldType']}
                      style={{ marginBottom: 0 }}
                    >
                      <Select
                        options={FIELD_TYPE_OPTIONS}
                        style={{ width: 100 }}
                      />
                    </Form.Item>
                    <Form.Item
                      name={[field.name, 'required']}
                      valuePropName="checked"
                      style={{ marginBottom: 0 }}
                    >
                      <Checkbox>必填</Checkbox>
                    </Form.Item>
                    <MinusCircleOutlined
                      onClick={() => remove(field.name)}
                      style={{ color: 'var(--text-secondary)' }}
                    />
                  </Space>
                ))}
                <Button
                  type="dashed"
                  block
                  icon={<PlusOutlined />}
                  onClick={() => add({ required: false, fieldType: 'TEXT' })}
                >
                  添加字段
                </Button>
              </>
            )}
          </Form.List>
        </Form>
      </FormSurface>
    </>
  )
}
