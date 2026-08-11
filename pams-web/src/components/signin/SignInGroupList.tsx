import { useCallback, useEffect, useState } from 'react'
import { App, Button, Checkbox, Collapse, Empty, Input, Popconfirm, Space, Tag } from 'antd'
import {
  DeleteOutlined,
  EditOutlined,
  FolderOutlined,
  SearchOutlined,
  UploadOutlined,
} from '@ant-design/icons'
import GlassModal from '@/components/glass/GlassModal'
import {
  deleteSignInGroup,
  deleteSignInGroups,
  deleteSignInPerson,
  deleteSignInPersons,
  listSignInGroups,
  renameSignInGroup,
  signInGroupSummary,
  uploadSignInGroup,
  type SignInGroupSummary,
  type SignInGroupVO,
} from '@/api/signinGroup'
import { useAuthStore } from '@/stores/auth'

interface SignInGroupListProps {
  activityId: number
  /** 父级刷新信号 */
  reloadKey?: number
  onChanged: () => void
}

/**
 * 签到分组折叠列表（PRD F02）
 * - 多次上传 → 自动分组（分组名=文件名）
 * - 折叠展示：分组名 + 人数 + 已签/未签 + 上传时间
 * - 支持分组级删除 / 批量删除 / 人员级删除 / 分组重命名 / 跨分组搜索
 */
export default function SignInGroupList({ activityId, reloadKey = 0, onChanged }: SignInGroupListProps) {
  const { message } = App.useApp()
  const [groups, setGroups] = useState<SignInGroupVO[]>([])
  const [summary, setSummary] = useState<SignInGroupSummary | null>(null)
  const [keyword, setKeyword] = useState('')
  const [batchMode, setBatchMode] = useState(false)
  const [checkedGroups, setCheckedGroups] = useState<number[]>([])
  const [renameTarget, setRenameTarget] = useState<SignInGroupVO | null>(null)
  const [renameName, setRenameName] = useState('')
  const [renaming, setRenaming] = useState(false)
  const [uploading, setUploading] = useState(false)
  const isMinisterOrAbove = (useAuthStore((s) => s.user?.roleLevel) ?? 0) >= 3

  const fetchData = useCallback(async () => {
    try {
      const [gs, sm] = await Promise.all([listSignInGroups(activityId, keyword || undefined), signInGroupSummary(activityId)])
      setGroups(gs ?? [])
      setSummary(sm ?? null)
    } catch {
      /* http 拦截已提示 */
    }
  }, [activityId, keyword, reloadKey])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  const handleUpload = async (file: File) => {
    setUploading(true)
    try {
      const res = await uploadSignInGroup(activityId, file)
      message.success(`已上传「${res.groupName}」：新增 ${res.added} 人${res.skipped ? `，跳过重复 ${res.skipped} 人` : ''}`)
      onChanged()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setUploading(false)
    }
  }

  const handleDeleteGroup = async (id: number) => {
    try {
      await deleteSignInGroup(id)
      message.success('分组已删除')
      onChanged()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleBatchDelete = async () => {
    if (checkedGroups.length === 0) return
    try {
      await deleteSignInGroups(checkedGroups)
      message.success(`已删除 ${checkedGroups.length} 个分组`)
      setCheckedGroups([])
      setBatchMode(false)
      onChanged()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleDeletePerson = async (rosterId: number) => {
    try {
      await deleteSignInPerson(rosterId)
      message.success('已删除该人员')
      onChanged()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleDeletePersons = async (group: SignInGroupVO, ids: number[]) => {
    try {
      await deleteSignInPersons(ids)
      message.success(`已从「${group.groupName}」删除 ${ids.length} 人`)
      onChanged()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleRename = async () => {
    if (!renameTarget) return
    if (!renameName.trim()) {
      message.warning('分组名不能为空')
      return
    }
    setRenaming(true)
    try {
      await renameSignInGroup(renameTarget.id, renameName.trim())
      message.success('分组已重命名')
      setRenameTarget(null)
      onChanged()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setRenaming(false)
    }
  }

  // 分组内人员多选删除
  const [personSelection, setPersonSelection] = useState<Record<number, number[]>>({})

  const collapsibleItems = groups.map((g) => ({
    key: String(g.id),
    label: (
      <Space size={8} wrap>
        {batchMode ? (
          <Checkbox
            checked={checkedGroups.includes(g.id)}
            onChange={(e) => {
              setCheckedGroups((prev) => (e.target.checked ? [...prev, g.id] : prev.filter((x) => x !== g.id)))
            }}
            onClick={(e) => e.stopPropagation()}
          />
        ) : (
          <FolderOutlined style={{ color: 'var(--color-primary)' }} />
        )}
        <span style={{ fontWeight: 600 }}>{g.groupName}</span>
        <Tag color="blue">{g.count} 人</Tag>
        <Tag color="green">已签 {g.signedCount}</Tag>
        <Tag color="orange">未签 {g.unsignedCount}</Tag>
        <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
          上传于 {g.createdAt ? new Date(g.createdAt).toLocaleString('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }) : '-'}
        </span>
        {!batchMode && (
          <Space size={0} onClick={(e) => e.stopPropagation()}>
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => {
                setRenameTarget(g)
                setRenameName(g.groupName)
              }}
            />
            <Popconfirm title={`确认删除分组「${g.groupName}」及其 ${g.count} 名人员？`} onConfirm={() => handleDeleteGroup(g.id)} okText="删除" cancelText="取消">
              <Button type="link" size="small" danger icon={<DeleteOutlined />} />
            </Popconfirm>
          </Space>
        )}
      </Space>
    ),
    children: (
      <div>
        <div style={{ marginBottom: 8 }}>
          <Space wrap size={8}>
            <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>选择要删除的人员：</span>
            {(personSelection[g.id] ?? []).length > 0 && (
              <Popconfirm
                title={`确认删除选中的 ${(personSelection[g.id] ?? []).length} 名人员？`}
                onConfirm={() => handleDeletePersons(g, personSelection[g.id] ?? [])}
                okText="删除"
                cancelText="取消"
              >
                <Button size="small" danger>
                  删除选中（{(personSelection[g.id] ?? []).length}）
                </Button>
              </Popconfirm>
            )}
          </Space>
        </div>
        <div style={{ display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {(g.people ?? []).map((p) => {
            const sel = personSelection[g.id] ?? []
            const checked = sel.includes(p.id)
            return (
              <span
                key={p.id}
                style={{
                  display: 'inline-flex',
                  alignItems: 'center',
                  gap: 4,
                  padding: '2px 8px',
                  border: '1px solid var(--color-border)',
                  borderRadius: 6,
                  background: checked ? 'rgba(245,34,45,.08)' : 'transparent',
                  cursor: 'pointer',
                  fontSize: 12,
                }}
                onClick={() => {
                  setPersonSelection((prev) => {
                    const cur = prev[g.id] ?? []
                    const next = checked ? cur.filter((x) => x !== p.id) : [...cur, p.id]
                    return { ...prev, [g.id]: next }
                  })
                }}
              >
                <Checkbox checked={checked} style={{ marginRight: 2 }} />
                <span>{p.fields['姓名'] ?? '-'}</span>
                <span style={{ color: 'var(--color-text-secondary)' }}>{p.fields['学号'] ?? ''}</span>
                {p.signed ? <Tag color="green" style={{ margin: 0 }}>已签</Tag> : <Tag color="red" style={{ margin: 0 }}>未签</Tag>}
                <Popconfirm title="确认删除该人员？" onConfirm={() => handleDeletePerson(p.id)} okText="删除" cancelText="取消">
                  <Button type="link" size="small" danger icon={<DeleteOutlined />} style={{ padding: 0, height: 20 }} />
                </Popconfirm>
              </span>
            )
          })}
          {(g.people ?? []).length === 0 && <Empty description="该分组暂无人员" image={Empty.PRESENTED_IMAGE_SIMPLE} />}
        </div>
      </div>
    ),
  }))

  return (
    <div>
      {/* 工具栏 */}
      <Space style={{ marginBottom: 12 }} wrap>
        <Input.Search
          placeholder="跨分组搜索姓名"
          allowClear
          style={{ width: 220 }}
          prefix={<SearchOutlined />}
          onSearch={(v) => setKeyword(v)}
        />
        <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
          共 {summary?.groupCount ?? 0} 组 · {summary?.total ?? 0} 人（已签 {summary?.signed ?? 0} / 未签 {summary?.unsigned ?? 0}）
        </span>
        {isMinisterOrAbove && (
          <>
            <Button
              size="small"
              type={batchMode ? 'primary' : 'default'}
              danger={batchMode}
              onClick={() => {
                setBatchMode((v) => !v)
                setCheckedGroups([])
              }}
            >
              批量管理
            </Button>
            {batchMode && (
              <Popconfirm
                title={`确认删除选中的 ${checkedGroups.length} 个分组？`}
                onConfirm={handleBatchDelete}
                okText="删除"
                cancelText="取消"
                disabled={checkedGroups.length === 0}
              >
                <Button size="small" danger disabled={checkedGroups.length === 0}>
                  删除选中（{checkedGroups.length}）
                </Button>
              </Popconfirm>
            )}
          </>
        )}
      </Space>

      {/* 上传入口（部长及以上） */}
      {isMinisterOrAbove && (
        <div style={{ marginBottom: 12 }}>
          <label
            style={{
              display: 'inline-flex',
              alignItems: 'center',
              gap: 6,
              padding: '6px 16px',
              border: '1px dashed var(--color-primary)',
              borderRadius: 8,
              color: 'var(--color-primary)',
              cursor: 'pointer',
              fontSize: 13,
            }}
          >
            <UploadOutlined />
            {uploading ? '上传中…' : '上传名单（新分组）'}
            <input
              type="file"
              accept=".xlsx,.xls"
              style={{ display: 'none' }}
              disabled={uploading}
              onChange={(e) => {
                const f = e.target.files?.[0]
                if (f) handleUpload(f)
                e.target.value = ''
              }}
            />
          </label>
          <span style={{ marginLeft: 8, color: 'var(--color-text-secondary)', fontSize: 12 }}>
            支持 .xlsx / .xls，上传后自动按文件名分组
          </span>
        </div>
      )}

      {/* 折叠列表 */}
      <Collapse
        items={collapsibleItems}
        style={{ background: 'transparent' }}
        bordered={false}
        defaultActiveKey={groups.map((g) => String(g.id))}
      />

      {/* 重命名弹窗 */}
      <GlassModal
        title="重命名分组"
        open={!!renameTarget}
        onCancel={() => setRenameTarget(null)}
        footer={
          <Space>
            <Button onClick={() => setRenameTarget(null)}>取消</Button>
            <Button type="primary" loading={renaming} onClick={handleRename}>
              保存
            </Button>
          </Space>
        }
      >
        <Input value={renameName} onChange={(e) => setRenameName(e.target.value)} maxLength={100} placeholder="分组名称" />
      </GlassModal>
    </div>
  )
}
