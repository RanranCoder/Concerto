import { useEffect, useState } from 'react'
import { AutoComplete, Button, Form, Input, Popconfirm, Select, Space, Switch, message } from 'antd'
import { DeleteOutlined, PlusOutlined, SettingOutlined } from '@ant-design/icons'
import GlassModal from '@/components/glass/GlassModal'
import { getSigninFields, saveSigninFields } from '@/api/signin'

interface FieldRow {
  fieldName: string
  fieldKey: string
  required: boolean
  fieldType: string
}

/** 预设字段：fieldName 与后端 fieldValueOfSignin 的 5 个可匹配字段一一映射 */
const FIELD_PRESETS: Array<{ fieldName: string; fieldKey: string; fieldType: string }> = [
  { fieldName: '姓名', fieldKey: 'name', fieldType: 'TEXT' },
  { fieldName: '学号', fieldKey: 'studentNo', fieldType: 'TEXT' },
  { fieldName: '手机号', fieldKey: 'phone', fieldType: 'PHONE' },
  { fieldName: '班级', fieldKey: 'className', fieldType: 'TEXT' },
  { fieldName: '身份', fieldKey: 'identityType', fieldType: 'TEXT' },
]

const FIELD_TYPE_OPTIONS = [
  { value: 'TEXT', label: '文本' },
  { value: 'NUMBER', label: '数字' },
  { value: 'PHONE', label: '手机号' },
]

/** 从预设列表查 name 对应的 key/type（未命中返回 null，fieldKey 由用户手填或回退字段名） */
function presetOf(name: string): { fieldKey: string; fieldType: string } | null {
  return FIELD_PRESETS.find((p) => p.fieldName === name) ?? null
}

/** 无配置时的默认字段：姓名(必填) + 学号(选填) */
const DEFAULT_ROWS: FieldRow[] = [
  { fieldName: '姓名', fieldKey: 'name', required: true, fieldType: 'TEXT' },
  { fieldName: '学号', fieldKey: 'studentNo', required: false, fieldType: 'TEXT' },
]

/**
 * 核验字段配置面板（GlassModal）。
 * 打开时异步加载活动当前字段配置；无配置时默认「姓名(必填)+学号(选填)」。
 * 字段名用预设下拉（姓名/学号/手机号/班级/身份，映射后端可匹配列）或手填自定义名，
 * 可增删行、切换必填/选填与字段类型（TEXT/NUMBER/PHONE）。
 * 保存走 PUT /signins/fields?activityId= + body 为字段数组（与后端 SigninRosterController.saveFields 签名一致）。
 */
export default function SigninFieldConfig({
  activityId,
  onChanged,
}: {
  activityId: number
  /** 保存成功回调，父级刷新名单列表（列表列 = 字段名） */
  onChanged?: () => void
}) {
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(false)
  const [form] = Form.useForm<{ fields: FieldRow[] }>()

  // 打开弹窗时加载当前字段配置（Form 已随 open=true 挂载，destroyOnHidden 下关闭即卸载）
  useEffect(() => {
    if (!open) return
    let cancelled = false
    setLoading(true)
    getSigninFields(activityId)
      .then((rows) => {
        if (cancelled) return
        const init: FieldRow[] =
          rows && rows.length > 0
            ? rows.map((r) => ({ fieldName: r.fieldName, fieldKey: r.fieldKey, required: r.required, fieldType: r.fieldType || 'TEXT' }))
            : DEFAULT_ROWS
        form.setFieldsValue({ fields: init })
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
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, activityId])

  const handleSave = async () => {
    const values = await form.validateFields()
    const fields: FieldRow[] = (values.fields ?? []).filter((f) => f?.fieldName?.trim())
    if (fields.length === 0) {
      message.warning('请至少配置一个字段')
      return
    }
    // 字段名去重校验：Excel 表头按字段名定位列，重名会导致导入数据错位
    const names = fields.map((f) => f.fieldName.trim())
    if (new Set(names).size !== names.length) {
      message.warning('字段名不能重复')
      return
    }
    setSaving(true)
    try {
      await saveSigninFields(
        activityId,
        fields.map((f) => {
          const name = f.fieldName.trim()
          const preset = presetOf(name)
          return {
            fieldName: name,
            fieldKey: preset?.fieldKey ?? (f.fieldKey?.trim() || name),
            required: !!f.required,
            fieldType: f.fieldType || 'TEXT',
          }
        }),
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
          字段名建议从预设中选择（姓名/学号/手机号/班级/身份），上传的 Excel 表头需与此一致；必填字段缺失的列在导入时会报错。
        </div>
        <Form form={form} layout="vertical">
          <Form.List name="fields">
            {(items, { add, remove }) => (
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {items.map(({ key, name }) => (
                  <Space key={key} align="baseline" style={{ display: 'flex' }}>
                    <Form.Item name={[name, 'fieldName']} rules={[{ required: true, message: '请输入字段名' }]} style={{ flex: 1, marginBottom: 0 }}>
                      <AutoComplete
                        options={FIELD_PRESETS.map((p) => ({ value: p.fieldName }))}
                        placeholder="字段名"
                        style={{ width: '100%' }}
                        onChange={(v) => {
                          // 命中预设时同步 fieldKey/fieldType（供保存回填），类型仍可手动覆盖
                          const p = presetOf(v)
                          if (p) {
                            form.setFieldValue(['fields', name, 'fieldKey'], p.fieldKey)
                            form.setFieldValue(['fields', name, 'fieldType'], p.fieldType)
                          }
                        }}
                      />
                    </Form.Item>
                    <Form.Item name={[name, 'fieldKey']} hidden>
                      <Input />
                    </Form.Item>
                    <Form.Item name={[name, 'required']} valuePropName="checked" style={{ marginBottom: 0 }}>
                      <Switch checkedChildren="必填" unCheckedChildren="选填" />
                    </Form.Item>
                    <Form.Item name={[name, 'fieldType']} style={{ marginBottom: 0, width: 110 }}>
                      <Select options={FIELD_TYPE_OPTIONS} />
                    </Form.Item>
                    <Popconfirm title="删除该字段？" onConfirm={() => remove(name)} okText="删除" cancelText="取消">
                      <Button type="text" danger icon={<DeleteOutlined />} />
                    </Popconfirm>
                  </Space>
                ))}
                <Button type="dashed" icon={<PlusOutlined />} onClick={() => add({ required: false, fieldType: 'TEXT' })}>
                  添加字段
                </Button>
              </div>
            )}
          </Form.List>
        </Form>
        {loading && <div style={{ textAlign: 'center', color: 'var(--color-text-secondary)' }}>加载中…</div>}
      </GlassModal>
    </>
  )
}
