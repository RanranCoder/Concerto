import { useRef, useState } from 'react'
import { Alert, App, Form, Input, Select } from 'antd'
import FormSurface, { FormSection } from '@/components/form/FormSurface'
import { errorText } from '@/components/admin/AdminSurface'
import { kbCreateDocument, kbUpdateDocument, type KbDocumentRequest, type KbDocumentVO } from '@/api/knowledge'

export default function KnowledgeDocumentEditor({document, onClose, onSaved}: {document?: KbDocumentVO; onClose: () => void; onSaved: () => void}) {
  const {message, modal} = App.useApp(), [form] = Form.useForm<KbDocumentRequest>()
  const [saving, setSaving] = useState(false), [error, setError] = useState(''), running = useRef(false)
  const close = () => {if (saving) return; if (!form.isFieldsTouched()) return onClose(); modal.confirm({title: '放弃未保存的文档修改？', okText: '放弃修改', cancelText: '继续编辑', onOk: onClose})}
  const save = async (values: KbDocumentRequest) => {
    if (running.current) return
    running.current = true; setSaving(true); setError('')
    try {
      const data: KbDocumentRequest = {title: values.title.trim(), body: values.body.trim(), docType: values.docType, category: values.category?.trim(), summary: values.summary?.trim(), visibility: values.visibility, version: values.version?.trim(), status: document?.status || 'PUBLISHED', requiredPermission: document?.requiredPermission || undefined, effectiveFrom: document?.effectiveFrom, effectiveTo: document?.effectiveTo}
      if (document) await kbUpdateDocument(document.id, data); else await kbCreateDocument(data)
      message.success('文档已保存并建立索引'); onSaved()
    } catch (err) {setError(errorText(err, '文档保存失败，请重试。'))} finally {running.current = false; setSaving(false)}
  }
  return <FormSurface mode="drawer" title={document ? '编辑文档' : '录入文档'} description="保留来源和章节条款，便于灵思引用后核对。" open onCancel={close} onOk={() => form.submit()} okText="保存并建立索引" confirmLoading={saving} cancelButtonProps={{disabled: saving}} keyboard={!saving} closable={!saving}>
    {error && <Alert showIcon type="error" message={error} className="admin-form-error" />}
    <Form form={form} layout="vertical" preserve={false} onFinish={save} initialValues={document || {docType: 'REGULATION', visibility: 'ALL'}}>
      <FormSection title="文档信息"><Form.Item name="title" label="文档标题" rules={[{required: true, whitespace: true, message: '请输入文档标题'}]}><Input maxLength={200} placeholder="例如：学生组织活动材料归档指引" /></Form.Item>
        <Form.Item name="docType" label="文档类型"><Select options={[{value: 'REGULATION', label: '制度'}, {value: 'POLICY', label: '上级文件'}, {value: 'MANUAL', label: '操作指引'}, {value: 'FAQ', label: '常见问答'}]} /></Form.Item>
        <Form.Item name="category" label="业务分类"><Input maxLength={50} placeholder="例如：党员发展" /></Form.Item>
        <Form.Item name="version" label="版本"><Input maxLength={50} placeholder="例如：2026 年修订版" /></Form.Item>
        <Form.Item name="summary" label="摘要"><Input.TextArea maxLength={500} rows={2} /></Form.Item></FormSection>
      <FormSection title="文档正文"><Form.Item name="body" label="正文" rules={[{required: true, whitespace: true, message: '请输入完整正文'}]}><Input.TextArea rows={12} placeholder="粘贴完整正文，保留章节和条款编号。" /></Form.Item></FormSection>
      <FormSection title="可见范围"><Form.Item name="visibility" label="可见范围" extra="本组织内符合授权条件的用户可检索。"><Select options={[{value: 'ALL', label: '全员可见'}, {value: 'LEADER', label: '部长及以上'}]} /></Form.Item>{document?.requiredPermission && <p className="admin-note">保留此文档的附加访问权限要求：{document.requiredPermission}</p>}</FormSection>
    </Form>
  </FormSurface>
}
