import { useRef, useState } from 'react'
import { App, Button, Skeleton } from 'antd'
import { Link } from 'react-router-dom'
import { updateExtensionPack } from '@/api/org'
import { errorText, LoadError, Panel, StatusTag } from '@/components/admin/AdminSurface'
import FormSurface from '@/components/form/FormSurface'
import { refreshAuthorization } from '@/hooks/useAuthorizationSync'
import { useAuthStore } from '@/stores/auth'
import { useOrgStore } from '@/stores/org'
import { EXTENSION_PACKS, type ExtensionPack } from '@/utils/extensionPacks'
import './extension-settings.css'

export default function ExtensionSettings() {
  const { message } = App.useApp()
  const { enabledPacks, loaded, loadFailed, load } = useOrgStore()
  const permissions = useAuthStore(s => s.user?.permissions) ?? []
  const [selected, setSelected] = useState<ExtensionPack | null>(null)
  const [change, setChange] = useState<boolean | null>(null)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState('')
  const busy = useRef(false)
  const enabled = !!selected && enabledPacks.includes(selected.code)

  async function save() {
    if (!selected?.available || change === null || busy.current) return
    const token = useAuthStore.getState().token
    busy.current = true
    setSaving(true)
    setError('')
    try {
      const packs = await updateExtensionPack(selected.code, change)
      if (useAuthStore.getState().token !== token) return
      useOrgStore.getState().applyPacks(packs)
      void refreshAuthorization().catch(() => {})
      setChange(null)
      setSelected(null)
      message.success(`${selected.name}已${change ? '启用' : '关闭'}`)
    } catch (err) {
      setError(errorText(err, '无法保存扩展功能设置，请重试。'))
    } finally {
      busy.current = false
      setSaving(false)
    }
  }

  const action = change ? '启用' : '关闭'
  return <>
    <div className="extension-panel"><Panel title="扩展功能" description="为当前组织启用所需的专项功能。关闭后，原有记录与配置会保留。">
      {loadFailed ? <LoadError message="暂时无法读取组件包状态" retry={load} /> :
        !loaded ? <Skeleton title={false} paragraph={{ rows: 3 }} /> :
        <ul className="extension-list" aria-label="组件包">
          {EXTENSION_PACKS.map(pack => {
            const active = enabledPacks.includes(pack.code)
            return <li key={pack.code} className="extension-row">
              <div><h4>{pack.name}</h4><p>{pack.description}</p></div>
              <div><StatusTag tone={active ? 'brand' : 'neutral'}>
                {!pack.available ? '筹备中' : active ? '已启用' : '未启用'}
              </StatusTag></div>
              <Button onClick={() => setSelected(pack)} aria-label={`${active ? '管理' : '查看'}${pack.name}`}>
                {active ? '管理' : '查看详情'}
              </Button>
            </li>
          })}
        </ul>}
    </Panel></div>
    <FormSurface mode="drawer" width={480} open={!!selected && change === null}
      title={selected?.name} description={selected?.description} onCancel={() => setSelected(null)}
      footer={selected?.available && loaded ? <Button type={enabled ? 'default' : 'primary'} danger={enabled}
        onClick={() => setChange(!enabled)}>{enabled ? '关闭功能' : '启用功能'}</Button> : undefined}>
      {selected && <div className="extension-details">
        <h3>包含能力</h3>
        <ul>{selected.features.map(feature => <li key={feature}>{feature}</li>)}</ul>
        {selected.available ? <>
          <h3>工作入口</h3>
          <p>启用后，拥有查看权限的成员可从侧栏进入{selected.workspace}。</p>
          {enabled && permissions.includes(`${selected.code}:view`) && <Link to={selected.entry}>进入{selected.workspace}</Link>}
          {selected.code !== 'party' && <p>组织所有者可使用并授权该包的查看、维护和审核权限。</p>}
          <h3>已有记录</h3>
          <p>关闭会隐藏{selected.workspace}入口，重新启用后可继续使用已有记录和权限。</p>
        </> : <p>该组件包正在筹备，开放后可在此启用。</p>}
      </div>}
    </FormSurface>
    <FormSurface open={change !== null} title={`${action}${selected?.name ?? ''}？`}
      onCancel={() => { setChange(null); setError('') }} onOk={save} okText={`确认${action}`}
      confirmLoading={saving} closable={!saving} keyboard={!saving}
      okButtonProps={{ danger: change === false }} cancelButtonProps={{ disabled: saving }}>
      <p>{change ? `具有查看权限的成员将看到${selected?.workspace ?? ''}入口，已有记录和配置可继续使用。` :
        `${selected?.workspace ?? ''}入口及相关功能将隐藏。已有业务记录、配置和关联关系将保留。`}</p>
      {error && <p className="extension-error" role="alert">{error}</p>}
    </FormSurface>
  </>
}
