import { useEffect, useState } from 'react'
import { App, Avatar, Button, Segmented, Skeleton, Space, Typography } from 'antd'
import { ApartmentOutlined, BookOutlined, CheckOutlined, DatabaseOutlined, FolderOutlined, ReloadOutlined, SettingOutlined, SkinOutlined, UserOutlined, WarningOutlined } from '@ant-design/icons'
import { useBlocker, useNavigate, useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import PageHeader from '@/components/glass/PageHeader'
import FormSurface from '@/components/form/FormSurface'
import { InfoList, LoadError, Panel, RelatedLink, StatusTag } from '@/components/admin/AdminSurface'
import { getSystemInfo, type SystemInfoVO } from '@/api/system'
import { getCurrentOrg, type OrgInfo } from '@/api/org'
import { kbStats, type KnowledgeStatsVO } from '@/api/knowledge'
import { useAuthStore } from '@/stores/auth'
import { parsePacks, useOrgStore } from '@/stores/org'
import { usePreferencesStore, type ListDensity } from '@/stores/preferences'
import { useThemeStore } from '@/stores/theme'

const categories = [{key: 'overview', label: '运行概览', icon: <DatabaseOutlined aria-hidden="true" />}, {key: 'organization', label: '组织与功能', icon: <ApartmentOutlined aria-hidden="true" />}, {key: 'preferences', label: '使用偏好', icon: <SkinOutlined aria-hidden="true" />}, {key: 'diagnostics', label: '存储与诊断', icon: <FolderOutlined aria-hidden="true" />}]
const packNames: Record<string, string> = {party: '党建包 · 党务台账', volunteer: '志愿服务包', club: '社团包'}

export default function Settings() {
  const {message} = App.useApp(), navigate = useNavigate(), [params, setParams] = useSearchParams()
  const tab = categories.some(item => item.key === params.get('section')) ? params.get('section')! : 'overview'
  const user = useAuthStore(s => s.user), mode = useThemeStore(s => s.mode), setMode = useThemeStore(s => s.setMode)
  const orgName = useOrgStore(s => s.orgName), primary = useOrgStore(s => s.primaryColor)
  const savedDensity = usePreferencesStore(s => s.userListDensity), saveDensity = usePreferencesStore(s => s.setUserListDensity)
  const [density, setDensity] = useState<ListDensity>(savedDensity), dirty = density !== savedDensity
  const blocker = useBlocker(({currentLocation, nextLocation}) => dirty && currentLocation.pathname !== nextLocation.pathname)
  const [info, setInfo] = useState<SystemInfoVO | null>(), [org, setOrg] = useState<OrgInfo | null>(), [knowledge, setKnowledge] = useState<KnowledgeStatsVO | null>()
  const [loading, setLoading] = useState(true), [checkedAt, setCheckedAt] = useState<string>(), [refresh, setRefresh] = useState(0)
  const canKnowledge = user?.permissions.includes('kb:read') ?? false
  const canUsers = user?.permissions.includes('user:view') ?? false
  const canRoles = user?.permissions.includes('role:view') ?? false
  const canOrg = user?.permissions.some(p => ['org:manage', 'org:structure:manage'].includes(p)) ?? false
  useEffect(() => {setDensity(savedDensity)}, [savedDensity])
  useEffect(() => {
    const handler = (event: BeforeUnloadEvent) => {if (dirty) {event.preventDefault(); event.returnValue = ''}}
    window.addEventListener('beforeunload', handler); return () => window.removeEventListener('beforeunload', handler)
  }, [dirty])
  useEffect(() => {
    let active = true; setLoading(true)
    Promise.allSettled([getSystemInfo(), getCurrentOrg(), canKnowledge ? kbStats() : Promise.resolve(null)]).then(([system, organization, kb]) => {
      if (!active) return
      setInfo(system.status === 'fulfilled' ? system.value : null)
      setOrg(organization.status === 'fulfilled' ? organization.value : null)
      setKnowledge(kb.status === 'fulfilled' ? kb.value : null)
      setCheckedAt(dayjs().format('YYYY-MM-DD HH:mm:ss'))
    }).finally(() => {if (active) setLoading(false)})
    return () => {active = false}
  }, [refresh, canKnowledge])
  const reload = () => setRefresh(v => v + 1)
  const switchTab = (key: string) => setParams(old => {const next = new URLSearchParams(old); next.set('section', key); return next})
  const healthy = info?.ping?.toLowerCase() === 'pong'
  const health = <StatusTag tone={loading ? 'neutral' : healthy ? 'success' : 'warning'}>{loading ? '检查中' : info ? healthy ? '连接正常' : '响应异常' : '状态不可用'}</StatusTag>
  const kbStatus = <StatusTag tone={knowledge ? knowledge.fulltextAvailable ? 'success' : 'warning' : 'neutral'}>{knowledge ? knowledge.fulltextAvailable ? '全文检索' : '模糊匹配' : canKnowledge ? '状态不可用' : '无查看权限'}</StatusTag>
  const save = () => {saveDensity(density); message.success('偏好已保存到当前浏览器')}
  const basicInfo = <InfoList items={[{label: '产品名称', value: '协奏 Concerto · 学生组织智能工作台'}, {label: '所属组织', value: org?.name || orgName || user?.orgName}, {label: '应用版本', value: info?.version || '—'}, {label: '当前登录', value: <Space wrap>{user?.realName}<StatusTag>{user?.isOwner ? '组织所有者' : user?.managementCapabilities.length ? '组织管理员' : '普通用户'}</StatusTag></Space>}, {label: '界面主题', value: mode === 'dark' ? '深色' : '浅色'}]} />
  const links = <div className="admin-link-list">{canOrg && <RelatedLink title="组织架构" description="维护部门、下级小组和负责人" icon={<ApartmentOutlined aria-hidden="true" />} onClick={() => navigate('/org/structure')} />}{canUsers && <RelatedLink title="用户管理" description="账号状态、部门归属与业务主角色" icon={<UserOutlined aria-hidden="true" />} onClick={() => navigate('/admin/users')} />}{canKnowledge && <RelatedLink title="知识库" description="资料内容、事实快照与检索记录" icon={<BookOutlined aria-hidden="true" />} onClick={() => navigate('/knowledge')} />}</div>
  return <div className="admin-v6 settings-v6"><PageHeader title="系统设置" description="查看系统运行情况，管理组织入口与个人使用偏好。" extra={<Button icon={<ReloadOutlined aria-hidden="true" />} loading={loading} onClick={reload}>刷新状态</Button>} />
    <div className="admin-settings-layout"><nav className="admin-rail" aria-label="系统设置分类"><h3>设置分类</h3>{categories.map(item => <button key={item.key} aria-pressed={tab === item.key} onClick={() => switchTab(item.key)}>{item.icon}{item.label}</button>)}</nav><div className="admin-settings-content">
      {info === null && <LoadError message="系统信息加载失败，运行状态暂不可用。" retry={reload} />}
      {tab === 'overview' && <>
        <section className="admin-panel"><div className="admin-running"><span className={`admin-health-icon ${healthy ? '' : 'warning'}`}>{healthy ? <CheckOutlined aria-hidden="true" /> : <WarningOutlined aria-hidden="true" />}</span><div><h3>{loading ? '正在检查系统服务' : healthy ? '系统基础健康检查通过' : '系统状态需要核对'}</h3><p>最近检查 {checkedAt || '—'} · 当前组织：{org?.shortName || org?.name || orgName || '—'}</p></div><Button type="link" onClick={() => switchTab('diagnostics')}>查看诊断</Button></div>
          <div className="admin-service-grid"><div><small>应用服务</small><strong>{health}</strong><small>基础响应检查</small></div><div><small>知识检索</small><strong>{kbStatus}</strong><small>维护与更新请前往知识库</small></div><div><small>文件存储</small><strong><StatusTag>{info?.uploadDir ? '目录已配置' : '配置未提供'}</StatusTag></strong><small>读写能力需单独核验</small></div></div></section>
        <Panel title="系统信息" description="运行信息与当前会话。">{loading && !info ? <Skeleton active /> : basicInfo}</Panel>
        <Panel title="常用管理入口" description="根据任务进入对应工作区。">{links}</Panel>
      </>}
      {tab === 'organization' && <>
        <Panel title="组织信息" description="组织资料与架构使用统一维护入口。" extra={canOrg && <Button onClick={() => navigate('/org/structure')}>前往组织架构</Button>}>
          {org === null ? <LoadError message="组织信息读取失败" retry={reload} /> : <InfoList items={[{label: '组织名称', value: org?.name || orgName}, {label: '组织简称', value: org?.shortName}, {label: '组织类型', value: org?.type}, {label: '品牌颜色', value: <span className="admin-color-sample"><i style={{background: primary}} />{primary}</span>}]} />}
        </Panel>
        <Panel title="功能与领域包" description="通用工作区与组织领域能力分开管理。"><ul className="admin-result-list"><li><span>活动、排班考勤、成员与档案</span><StatusTag tone="success">通用工作区</StatusTag></li><li><span>灵思与知识库</span><StatusTag tone="success">智能协作</StatusTag></li>{org && parsePacks(org.enabledPacks).map(pack => <li key={pack}><span>{packNames[pack] || pack}</span><StatusTag tone="brand">已启用</StatusTag></li>)}</ul><p className="admin-note">入口显示同时受领域包和账号权限控制。调整组织配置请前往组织管理核对。</p></Panel>
        {canRoles && <Panel title="授权管理"><RelatedLink title="角色与权限" description="业务授权、管理员身份与治理记录" icon={<SettingOutlined aria-hidden="true" />} onClick={() => navigate('/admin/permissions')} /></Panel>}
      </>}
      {tab === 'preferences' && <>
        <section className="admin-panel"><header><div><h3>界面与列表</h3><p>仅影响当前浏览器，不改变其他成员的使用设置。</p></div></header><div className="admin-panel-body">
          <div className="admin-preference-row"><div><strong>界面主题</strong><p>立即生效，与顶栏主题按钮保持同步。</p></div><Segmented aria-label="界面主题" value={mode} options={[{value: 'light', label: '浅色'}, {value: 'dark', label: '深色'}]} onChange={value => setMode(value as 'light' | 'dark')} /></div>
          <div className="admin-preference-row"><div><strong>用户列表密度</strong><p>标准模式便于阅读，紧凑模式在同屏显示更多账号。</p></div><Segmented aria-label="用户列表密度" value={density} options={[{value: 'standard', label: '标准'}, {value: 'compact', label: '紧凑'}]} onChange={value => setDensity(value as ListDensity)} /></div>
          <div className="admin-preference-row"><div><strong>预览效果</strong><p>保存后应用于用户管理列表。</p></div><div className={`admin-density-preview ${density}`}><Avatar>{user?.realName?.slice(0, 1)}</Avatar><div><strong>{user?.realName}</strong><small>{user?.username}</small></div><StatusTag tone="success">启用</StatusTag></div></div>
        </div><footer className="admin-savebar"><span>{dirty ? '有未保存的密度修改' : '偏好已保存到当前浏览器'}</span><Space><Button disabled={!dirty} onClick={() => setDensity(savedDensity)}>取消修改</Button><Button type="primary" disabled={!dirty} onClick={save}>保存偏好</Button></Space></footer></section>
        <Panel title="语言与时间" description="全站日期与时段采用一致口径。"><InfoList items={[{label: '界面语言', value: '简体中文'}, {label: '显示时区', value: 'Asia/Shanghai（UTC+08:00）'}, {label: '日期格式', value: 'YYYY-MM-DD · 24 小时制'}]} /></Panel><p className="admin-note">通知消息在通知中心查看，密码在个人账号入口维护。</p>
      </>}
      {tab === 'diagnostics' && <>
        <Panel title="文件存储" description="只读展示服务端配置。"><InfoList items={[{label: '上传目录', value: info?.uploadDir ? <Typography.Text copyable={{text: info.uploadDir}}>{info.uploadDir}</Typography.Text> : '未提供'}, {label: '目录来源', value: '部署环境配置'}, {label: '可用空间', value: '未提供监测数据'}, {label: '读写验证', value: '未执行文件读写测试'}]} /><p className="admin-note">目录路径不等于读写正常。如需调整目录，请由部署维护人员修改服务配置。</p></Panel>
        <Panel title="连接诊断" description={'最近检查 ' + (checkedAt || '—')} extra={<Button onClick={reload} loading={loading}>运行诊断</Button>}><div className="admin-diagnostic-row"><div><strong>系统信息</strong><small>应用版本与上传目录配置</small></div><StatusTag tone={info ? 'success' : 'warning'}>{info ? '可读取' : loading ? '检查中' : '读取失败'}</StatusTag></div><div className="admin-diagnostic-row"><div><strong>基础健康检查</strong><small>应用能够返回健康响应</small></div>{health}</div><div className="admin-diagnostic-row"><div><strong>数据库、磁盘与备份</strong><small>当前接口未提供专项监测</small></div><StatusTag>未检测</StatusTag></div><p className="admin-note">基础健康正常仅表明应用可响应，不能代替数据库、磁盘和备份检查。</p></Panel>
        <Panel title="维护入口" description="按业务范围定位问题。">{links}</Panel>
      </>}
    </div></div>
    <FormSurface title="离开前保存偏好？" description="当前用户列表密度尚未保存。" open={blocker.state === 'blocked'} onCancel={() => blocker.state === 'blocked' && blocker.reset()} footer={<Space wrap><Button onClick={() => blocker.state === 'blocked' && blocker.reset()}>继续编辑</Button><Button onClick={() => {setDensity(savedDensity); if (blocker.state === 'blocked') blocker.proceed()}}>放弃并离开</Button><Button type="primary" onClick={() => {saveDensity(density); if (blocker.state === 'blocked') blocker.proceed()}}>保存并离开</Button></Space>}><p>选择保存后离开，或放弃这次密度修改。</p></FormSurface>
  </div>
}
