import { useEffect, useRef, useState } from 'react'
import { App, Button, DatePicker, Form, Input, InputNumber, Modal, Select } from 'antd'
import dayjs from 'dayjs'
import { EditOutlined } from '@ant-design/icons'
import type { AgentFormField, AgentInputForm, TaskInputResult } from '@/api/agent'

interface Props {
  form: AgentInputForm
  busy?: boolean
  /** 提交表单：由 store.submitForm 实现；返回结果决定弹窗去留 */
  onSubmit: (values: Record<string, string>) => Promise<TaskInputResult>
}

/** 表单初始值：date 字段转 dayjs（DatePicker 契约），其余原样 */
function toInitialValues(form: AgentInputForm): Record<string, unknown> {
  const out: Record<string, unknown> = {}
  for (const f of form.fields) {
    const v = form.values?.[f.name]
    if (v == null || v === '') continue
    if (f.type === 'date') {
      const d = dayjs(String(v))
      out[f.name] = d.isValid() ? d : undefined
    } else {
      out[f.name] = v
    }
  }
  return out
}

/** 提交前归一化：dayjs → 'YYYY-MM-DD'，select(tags) 取第一项，number 转字符串 */
function normalize(fields: AgentFormField[], raw: Record<string, unknown>): Record<string, string> {
  const out: Record<string, string> = {}
  for (const f of fields) {
    const v = raw[f.name]
    if (v == null) continue
    if (f.type === 'date' && dayjs.isDayjs(v)) {
      out[f.name] = v.format('YYYY-MM-DD')
    } else if (f.type === 'select' && Array.isArray(v)) {
      if (v.length > 0) out[f.name] = String(v[0])
    } else if (v !== '') {
      out[f.name] = String(v)
    }
  }
  return out
}

/**
 * 表单补全卡（D2.5 Form Elicitation）——行内卡片 + 弹窗两层。
 *
 * 与 Claude/MCP elicitation、Codex 结构化确认同款交互范式：
 * 1. 行内卡说明"还缺什么、预填了什么"，消息流不被弹窗遮蔽；
 * 2. 弹窗按 fields schema 渲染（文本/下拉/日期/多行），带 * 必填校验；
 * 3. 提交后走既有确认门禁出清单——表单只收集参数，<b>不替代确认</b>。
 *
 * 硬约束：只依赖 AgentInputForm 结构，不感知具体业务 —— 新增表单型工具前端零改动（D11）。
 */
export default function LingsiFormCard({ form, busy, onSubmit }: Props) {
  const { message } = App.useApp()
  const [antdForm] = Form.useForm()
  const [open, setOpen] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const openedFor = useRef<number | null>(null)

  // 表单到达（taskId 变化）自动弹出一次——引导式填写是本机制的目的；
  // 用户关闭后可随时点"打开表单"重开，不会反复骚扰
  useEffect(() => {
    if (form.taskId && openedFor.current !== form.taskId) {
      openedFor.current = form.taskId
      setOpen(true)
    }
  }, [form.taskId])

  const prefilled = form.fields.filter((f) => {
    const v = form.values?.[f.name]
    return v != null && v !== ''
  }).length

  const handleOk = async () => {
    try {
      const raw = await antdForm.validateFields()
      setSubmitting(true)
      const res = await onSubmit(normalize(form.fields, raw))
      if (res.status === 'ERROR') {
        message.error(res.error ?? '提交失败，请检查后重试')
        return // 保留弹窗让用户修改
      }
      if (res.status === 'CANCELLED') {
        message.info(res.message ?? '该操作已终止')
      }
      setOpen(false)
    } catch {
      // 校验失败：antd 已在字段上标红，无需重复提示
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="lingsi-form">
      <div className="lingsi-form-head">
        <EditOutlined />
        <span className="lingsi-form-title">{form.title || '需要补充信息'}</span>
        <span className="lingsi-form-summary">
          已预填 {prefilled} 项 · 还需你核对补全
        </span>
      </div>

      <div className="lingsi-form-sec">
        {form.description && <div className="lingsi-form-desc">{form.description}</div>}
        <div className="lingsi-form-fields">
          {form.fields.map((f) => (
            <span className="lingsi-form-field" key={f.name}>
              {f.label}
              {f.required && <em>*</em>}
              {form.values?.[f.name] != null && form.values[f.name] !== '' && (
                <span className="lingsi-form-prefill" title={String(form.values[f.name])}>
                  已预填
                </span>
              )}
            </span>
          ))}
        </div>
      </div>

      <div className="lingsi-form-foot">
        <span className="lingsi-form-summary">
          提交后将生成待确认清单，确认后才会执行
        </span>
        <Button size="small" type="primary" onClick={() => setOpen(true)}>
          打开表单
        </Button>
      </div>

      <Modal
        title={form.title || '需要补充信息'}
        open={open}
        onOk={handleOk}
        onCancel={() => setOpen(false)}
        destroyOnHidden
        maskClosable={false}
        width={520}
        okText="提交并生成清单"
        cancelText="稍后再填"
        confirmLoading={submitting || Boolean(busy)}
        okButtonProps={{ disabled: busy }}
      >
        <Form form={antdForm} layout="vertical" initialValues={toInitialValues(form)} requiredMark>
          {form.fields.map((f) => (
            <Form.Item
              key={f.name}
              name={f.name}
              label={f.label}
              rules={f.required ? [{ required: true, message: `${f.label}为必填` }] : undefined}
            >
              <FormFieldControl field={f} />
            </Form.Item>
          ))}
        </Form>
      </Modal>
    </div>
  )
}

/** 按 schema 类型渲染控件（select 允许自由输入自定义值） */
function FormFieldControl({ field }: { field: AgentFormField }) {
  const { placeholder } = field
  switch (field.type) {
    case 'textarea':
      return <Input.TextArea rows={3} placeholder={placeholder} maxLength={1000} />
    case 'date':
      return <DatePicker style={{ width: '100%' }} placeholder={placeholder ?? '选择日期'} />
    case 'select':
      return (
        <Select
          mode="tags"
          maxCount={1}
          options={(field.options ?? []).map((o) => ({ value: o, label: o }))}
          placeholder={placeholder ?? '选择或输入'}
        />
      )
    case 'number':
      return <InputNumber style={{ width: '100%' }} placeholder={placeholder} />
    default:
      return <Input placeholder={placeholder} maxLength={200} />
  }
}
