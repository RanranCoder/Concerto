import { useEffect, useRef, useState } from 'react'
import { Alert, App, Button, Empty, Form, Input, Select, Skeleton, Space, Switch } from 'antd'
import { PlusOutlined, ReloadOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import FormSurface, { FormSection } from '@/components/form/FormSurface'
import { errorText, InfoList, LoadError, Panel, StatusTag } from '@/components/admin/AdminSurface'
import { activateProvider, deleteProvider, listProviders, resetProvider, saveProvider, testProvider,
  type ProviderDraft, type ProviderListing, type ProviderTest, type ProviderView } from '@/api/agentProviders'
import './ai-services.css'

const protocols = [{ value: 'openai_chat_completions', label: 'Chat Completions' }, { value: 'openai_responses', label: 'Responses' }]
const protocolName = (value?: string) => protocols.find(p => p.value === value)?.label || '—'
const blank: ProviderDraft = { displayName: '', protocol: 'openai_chat_completions', baseUrl: '', modelId: '', apiKey: '', summaryEnabled: false }

export default function AiServices({ onDirty }: { onDirty: (dirty: boolean) => void }) {
  const { message, modal } = App.useApp()
  const [data, setData] = useState<ProviderListing>(), [loading, setLoading] = useState(true), [error, setError] = useState('')
  const [editing, setEditing] = useState<ProviderView | null>(), [dirty, setDirty] = useState(false)
  const [busy, setBusy] = useState(false), [testing, setTesting] = useState(false), [result, setResult] = useState<ProviderTest>()
  const [formError, setFormError] = useState(''), [form] = Form.useForm<ProviderDraft>()
  const revision = useRef(0), alive = useRef(true)
  const protocol = Form.useWatch('protocol', form)
  const opened = editing !== undefined
  const reportError = (e: unknown) => errorText(e, '操作失败，请重试')
  const reload = async () => {
    setLoading(true); setError('')
    try { const next = await listProviders(); if (alive.current) setData(next) }
    catch (e) { if (alive.current) setError(reportError(e)) }
    finally { if (alive.current) setLoading(false) }
  }
  useEffect(() => { alive.current = true; void reload(); return () => { alive.current = false } }, [])
  useEffect(() => { onDirty(dirty); return () => onDirty(false) }, [dirty, onDirty])
  const startEdit = (item: ProviderView | null) => {
    revision.current++; setEditing(item); setDirty(false); setResult(undefined); setFormError('')
    form.resetFields(); form.setFieldsValue(item ? { ...item, apiKey: '' } : blank)
  }
  const close = () => {
    const discard = () => { revision.current++; form.resetFields(); setEditing(undefined); setResult(undefined); setDirty(false) }
    if (dirty) modal.confirm({ title: '放弃未保存的服务配置？', content: '尚未保存的修改将丢失。', okText: '放弃修改', cancelText: '继续编辑', onOk: discard })
    else discard()
  }
  const changed = (values: Partial<ProviderDraft>) => {
    revision.current++; setDirty(true); setResult(undefined); setFormError('')
    if (values.protocol === 'openai_chat_completions') form.setFieldValue('summaryEnabled', false)
  }
  const draft = async () => ({ ...await form.validateFields(), id: editing?.id, version: editing?.version })
  const requireStorage = async () => {
    if (data?.encryptionReady) return true
    const current = await listProviders()
    if (alive.current) setData(current)
    if (current.encryptionReady) return true
    setFormError('密钥存储自动初始化失败，暂时无法测试或保存。请检查服务端存储状态后重试；当前填写内容已保留。')
    return false
  }
  const test = async () => {
    try {
      const value = await draft(); const rev = revision.current
      setTesting(true); setFormError(''); setResult(undefined)
      if (!await requireStorage()) return
      const response = await testProvider(value)
      if (alive.current && revision.current === rev) { setResult(response); setDirty(true) }
      if (!response.success && alive.current) await reload()
    } catch (e) { if (e && typeof e === 'object' && 'errorFields' in e) return; setFormError(reportError(e)) }
    finally { setTesting(false) }
  }
  const save = async () => {
    try {
      const value = await draft(); setBusy(true); setFormError('')
      if (!await requireStorage()) return
      await saveProvider({ ...value, testTicket: result?.success ? result.ticket || undefined : undefined })
      setDirty(false); form.resetFields(); setEditing(undefined); setResult(undefined)
      message.success('AI 服务已保存'); await reload()
    } catch (e) { if (e && typeof e === 'object' && 'errorFields' in e) return; setFormError(reportError(e)) }
    finally { setBusy(false) }
  }
  const mutate = async (operation: () => Promise<void>, success: string) => {
    setBusy(true)
    try { await operation(); message.success(success); await reload() }
    catch (e) { message.error(reportError(e)); await reload() }
    finally { setBusy(false) }
  }
  const active = data?.items.find(item => item.id === data.defaultId)
  return <div className="ai-services">
    <Panel title="当前 AI 服务" description="当前组织的 Concerto、知识库问答与协作任务共用此服务。">
      {loading && !data ? <Skeleton active paragraph={{ rows: 2 }} /> : data && <>
        <InfoList items={[
          { label: '当前服务', value: <Space wrap><strong>{active?.displayName || '部署配置'}</strong><StatusTag tone={active || data.deploymentAvailable ? 'success' : 'neutral'}>{active ? '组织默认' : data.deploymentAvailable ? '部署默认' : '规则模式'}</StatusTag></Space> },
          { label: '接口协议', value: protocolName(active?.protocol || data.deploymentProtocol) },
          { label: '请求模型', value: active?.modelId || (data.deploymentAvailable ? data.deploymentModel : '—') },
          { label: '最近测试', value: active?.testedAt ? `${dayjs(active.testedAt).format('YYYY-MM-DD HH:mm:ss')} · ${active.testStatus === 'PASSED' ? '测试通过' : '测试失败'}` : '未提供测试记录' },
        ]} />
        <p className="admin-note">设置更新后，新任务使用新配置；正在执行的任务继续使用原配置。最近测试结果不代表实时可用状态。</p>
        {active && <Button disabled={busy} onClick={() => modal.confirm({ title: '恢复部署配置？', content: '后续任务将使用部署维护人员配置的服务；已有服务配置仍会保留。', okText: '恢复部署配置', cancelText: '取消', onOk: () => mutate(() => resetProvider(data.defaultVersion), '已恢复部署配置') })}>恢复部署配置</Button>}
      </>}
    </Panel>
    {error && <LoadError message={error} retry={() => void reload()} />}
    {data && !data.encryptionReady && <Alert showIcon type="warning" message="密钥存储尚未就绪" description="系统自动初始化未完成，请检查服务端存储状态。可以先新增并填写信息，存储恢复后重试。" />}
    <Panel title="服务配置" description="选择原生接口协议，填写服务端点和请求模型。" extra={<Space wrap><Button aria-label="刷新服务配置" icon={<ReloadOutlined />} disabled={busy || testing} loading={loading} onClick={() => void reload()} /><Button type="primary" icon={<PlusOutlined />} disabled={busy || testing} onClick={() => startEdit(null)}>新增服务</Button></Space>}>
      {loading && !data ? <Skeleton active /> : data?.items.length ? <div className="ai-service-list">{data.items.map(item => <article className="ai-service-record" key={item.id}>
        <div className="ai-service-record-heading"><strong>{item.displayName}</strong><Space wrap>{item.id === data.defaultId && <StatusTag tone="brand">默认服务</StatusTag>}<StatusTag tone={item.testStatus === 'PASSED' ? 'success' : item.testStatus === 'FAILED' ? 'danger' : 'neutral'}>{item.testStatus === 'PASSED' ? '测试通过' : item.testStatus === 'FAILED' ? '测试失败' : '未验证'}</StatusTag></Space></div>
        <dl className="ai-service-fields"><div><dt>接口协议</dt><dd>{protocolName(item.protocol)}</dd></div><div><dt>请求模型</dt><dd>{item.modelId}</dd></div><div className="ai-service-url"><dt>Base URL</dt><dd>{item.baseUrl}</dd></div></dl>
        <div className="ai-service-record-footer"><span className="admin-note">{item.testedAt ? `最近测试 ${dayjs(item.testedAt).format('MM-DD HH:mm')}` : '尚未测试连接'}{item.summaryEnabled ? ` · 推理摘要${item.testSummary ? '可用' : '未返回'}` : ''}</span><Space wrap>
          <Button disabled={busy || testing} onClick={() => startEdit(item)}>编辑 / 测试</Button>
          {item.id !== data.defaultId && <Button disabled={busy || item.testStatus !== 'PASSED'} onClick={() => modal.confirm({ title: `将「${item.displayName}」设为默认服务？`, content: '将应用于当前组织后续启动的所有 AI 任务。', okText: '设为默认', cancelText: '取消', onOk: () => mutate(() => activateProvider(item, data.defaultVersion), '默认 AI 服务已更新') })}>设为默认</Button>}
          <Button danger disabled={busy || item.id === data.defaultId} onClick={() => modal.confirm({ title: `删除「${item.displayName}」？`, content: '此配置及已保存的密钥将被删除。', okText: '删除', okButtonProps: { danger: true }, cancelText: '取消', onOk: () => mutate(() => deleteProvider(item), '服务配置已删除') })}>删除</Button>
        </Space></div>
      </article>)}</div> : !error && <Empty image={Empty.PRESENTED_IMAGE_SIMPLE} description="尚未添加 AI 服务，当前沿用部署配置。" />}
    </Panel>
    <FormSurface mode="drawer" width={640} title={editing ? '编辑 AI 服务' : '新增 AI 服务'} description="配置当前组织使用的 AI 服务，保存后可设为默认。" open={opened} forceRender onCancel={busy || testing ? undefined : close} closable={!busy && !testing} keyboard={!busy && !testing} footer={<div className="ai-service-editor-footer"><span>{dirty ? '有未保存的修改' : '修改后需保存'}</span><Space wrap><Button disabled={busy || testing} onClick={close}>取消</Button><Button loading={testing} disabled={busy} onClick={() => void test()}>测试连接</Button><Button type="primary" loading={busy} disabled={testing} onClick={() => void save()}>保存配置</Button></Space></div>}>
      {data && !data.encryptionReady && !formError && <Alert showIcon type="warning" message="密钥存储尚未就绪" description="系统自动初始化未完成。可先填写信息，服务端存储恢复后重试。" />}
      <Form form={form} layout="vertical" disabled={busy || testing} initialValues={blank} onValuesChange={changed} autoComplete="off">
        <FormSection title="服务信息" description="显示名称用于系统标识，请求模型 ID 原样发送给服务。">
          <Form.Item name="displayName" label="显示名称" rules={[{ required: true, whitespace: true, message: '请填写显示名称' }, { max: 50 }]}><Input maxLength={50} placeholder="例如：组织智能助手" /></Form.Item>
          <Form.Item name="protocol" label="接口协议" rules={[{ required: true }]}><Select options={protocols} /></Form.Item>
          <Form.Item name="baseUrl" label="Base URL" extra={`填写 API 根地址；系统追加 ${protocol === 'openai_responses' ? '/responses' : '/chat/completions'}。`} rules={[{ required: true, whitespace: true, message: '请填写 API 根地址' }, { max: 2048 }, { validator: (_, value: string) => {
            if (!value) return Promise.resolve()
            try { const url = new URL(value.trim()); if (!['https:', 'http:'].includes(url.protocol) || url.username || url.password || url.search || url.hash || /\/(chat\/completions|responses)\/?$/.test(url.pathname)) throw new Error(); return Promise.resolve() }
            catch { return Promise.reject(new Error('请填写有效 API 根地址，不包含完整接口路径或查询参数')) }
          } }]}><Input maxLength={2048} placeholder="https://api.example.com/v1" /></Form.Item>
          <Form.Item name="apiKey" label="API Key" extra={editing ? '密钥已保存。留空保留原密钥，填写则替换。' : '密钥由服务端加密保存，不会在后续读取时返回。'} rules={[{ required: !editing, whitespace: true, message: '请填写 API Key' }, { max: 4096 }]}><Input.Password autoComplete="new-password" placeholder={editing ? '留空保留已保存的密钥' : '填写服务 API Key'} /></Form.Item>
          <Form.Item name="modelId" label="请求模型 ID" rules={[{ required: true, whitespace: true, message: '请填写服务商提供的模型 ID' }, { max: 200 }]}><Input maxLength={200} placeholder="填写准确的模型 ID，区分大小写" /></Form.Item>
          {protocol === 'openai_responses' && <Form.Item name="summaryEnabled" label="请求推理摘要" valuePropName="checked" extra="仅支持此能力的模型可返回摘要；摘要不是完整思维链。"><Switch /></Form.Item>}
        </FormSection>
      </Form>
      <p className="admin-note">测试将发送少量固定内容并验证流式响应与工具调用，可能产生少量费用，不发送组织业务资料。保存后可在列表中设为默认。</p>
      {formError && <Alert type="error" showIcon message={formError} />}
      {result && <Alert showIcon type={result.success ? 'success' : 'error'} message={result.message} description={`${result.durationMs} ms${protocol === 'openai_responses' ? ` · 推理摘要${result.summaryAvailable ? '已返回' : '未返回'}` : ''}${result.success ? ' · 请保存当前配置以保留测试结果' : ''}`} />}
    </FormSurface>
  </div>
}
