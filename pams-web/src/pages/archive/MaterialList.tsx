import { useEffect, useMemo, useState } from 'react'
import { Button, Empty, Form, Input, message, Popconfirm, Select, Space, Spin, Tree } from 'antd'
import {
  DeleteOutlined,
  DownloadOutlined,
  FileOutlined,
  FolderOutlined,
  FolderOpenOutlined,
  PlusOutlined,
  SearchOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import PageHeader from '@/components/glass/PageHeader'
import UploadFile from '@/components/glass/UploadFile'
import {
  createMaterial,
  deleteMaterial,
  getMaterialTree,
  listMaterials,
  MATERIAL_BIZ_TYPE_MAP,
  MATERIAL_BIZ_TYPE_OPTIONS,
  type MaterialTreeActivityNode,
  type MaterialVO,
} from '@/api/material'
import { listActivities, type ActivityVO } from '@/api/activity'
import { downloadFile } from '@/api/file'

interface MaterialFormValues {
  name: string
  bizType: string
  activityId?: number
  tag?: string
}

export default function MaterialList() {
  const [keyword, setKeyword] = useState('')
  const [tree, setTree] = useState<MaterialTreeActivityNode[]>([])
  const [flat, setFlat] = useState<MaterialVO[]>([])
  const [flatTotal, setFlatTotal] = useState(0)
  const [activities, setActivities] = useState<ActivityVO[]>([])
  const [uploadOpen, setUploadOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [loading, setLoading] = useState(false)
  const [fileId, setFileId] = useState<number | null>(null)
  const [form] = Form.useForm<MaterialFormValues>()

  const activityNameOf = (id: number | null): string => {
    if (id == null) return '未关联活动'
    return activities.find((a) => a.id === id)?.name ?? `#${id}`
  }
  const activityOptions = useMemo(
    () => activities.map((a) => ({ value: a.id, label: a.name })),
    [activities],
  )

  const fetchTree = () => {
    setLoading(true)
    getMaterialTree()
      .then((res) => setTree(res ?? []))
      .catch(() => {
        /* http 拦截已提示 */
      })
      .finally(() => setLoading(false))
  }

  // 有搜索关键字时后端 tree 不参与过滤，改用分页列表
  const fetchFlat = () => {
    setLoading(true)
    listMaterials({ keyword: keyword || undefined, size: 100 })
      .then((res) => {
        setFlat(res.records ?? [])
        setFlatTotal(res.total)
      })
      .catch(() => {
        /* http 拦截已提示 */
      })
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    if (keyword.trim()) fetchFlat()
    else fetchTree()
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [keyword])

  // 树节点只返回 activityId，需另查活动列表做名字映射
  useEffect(() => {
    listActivities({ size: 100 })
      .then((res) => setActivities(res.records ?? []))
      .catch(() => {
        /* 干事可能无活动列表权限，树节点以 #id 兜底 */
      })
  }, [])

  const openUpload = () => {
    setFileId(null)
    form.resetFields()
    setUploadOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    if (!fileId) {
      message.warning('请先上传材料文件')
      return
    }
    setSaving(true)
    try {
      await createMaterial({
        name: values.name.trim(),
        bizType: values.bizType,
        activityId: values.activityId ?? null,
        tag: values.tag?.trim() || null,
        fileId,
      })
      message.success('材料已归档')
      setUploadOpen(false)
      if (keyword.trim()) fetchFlat()
      else fetchTree()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteMaterial(id)
      message.success('已删除')
      if (keyword.trim()) fetchFlat()
      else fetchTree()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const leafIcon = (m: MaterialVO) =>
    m.bizType === 'PHOTO' ? (
      <FileOutlined style={{ color: 'var(--color-primary)' }} />
    ) : (
      <FileOutlined style={{ color: 'var(--color-text-secondary)' }} />
    )

  // 树数据：活动（或未关联）→ 类型 → 材料叶子
  const treeData = useMemo(() => {
    return tree.map((activityNode) => {
      return {
        key: `activity-${activityNode.activityId ?? 'none'}`,
        title: (
          <Space size={4}>
            <FolderOutlined style={{ color: 'var(--color-primary)' }} />
            {activityNode.activityId == null ? '未关联活动' : activityNameOf(activityNode.activityId)}
          </Space>
        ),
        children: (activityNode.bizTypes ?? []).map((typeNode) => ({
          key: `activity-${activityNode.activityId ?? 'none'}-type-${typeNode.bizType}`,
          title: (
            <Space size={4}>
              <FolderOpenOutlined style={{ color: 'var(--color-primary)' }} />
              <span>{MATERIAL_BIZ_TYPE_MAP[typeNode.bizType] ?? typeNode.bizType}</span>
              <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
                （{typeNode.materials.length}）
              </span>
            </Space>
          ),
          children: typeNode.materials.map((m) => ({
            key: `material-${m.id}`,
            title: (
              <Space size={8}>
                {leafIcon(m)}
                <span>{m.name}</span>
                <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
                  {m.tag ? `#${m.tag}` : ''} · {dayjs(m.createdAt).format('MM-DD')}
                </span>
                {m.fileId && (
                  <Button
                    type="link"
                    size="small"
                    icon={<DownloadOutlined />}
                    onClick={() => downloadFile(m.fileId as number, m.name)}
                  />
                )}
                <Popconfirm title="确认删除该材料？" onConfirm={() => handleDelete(m.id)} okText="删除" cancelText="取消">
                  <Button type="link" size="small" danger icon={<DeleteOutlined />} />
                </Popconfirm>
              </Space>
            ),
          })),
        })),
      }
    })
  }, [tree, activities])

  return (
    <div>
      <PageHeader
        title="材料库"
        description="活动材料归档，按活动 / 类型分组，替代手工汇总包"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={openUpload}>
            上传材料
          </Button>
        }
      />

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <Input.Search
            placeholder="搜索材料名称"
            allowClear
            style={{ width: 260 }}
            prefix={<SearchOutlined />}
            onSearch={(v) => setKeyword(v)}
          />
          <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
            {keyword.trim() ? `按名称搜索到 ${flatTotal} 条` : '当前按活动 / 类型分组展示全部材料'}
          </span>
        </Space>
      </GlassCard>

      <Spin spinning={loading}>
        {keyword.trim() ? (
          <GlassCard style={{ padding: 16 }}>
            {flat.length === 0 ? (
              <Empty description="未找到匹配材料" />
            ) : (
              flat.map((m) => (
                <div key={m.id} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '10px 4px', borderBottom: '1px solid var(--color-border)' }}>
                  {leafIcon(m)}
                  <div style={{ flex: 1, minWidth: 0 }}>
                    <div style={{ overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>{m.name}</div>
                    <div style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
                      {MATERIAL_BIZ_TYPE_MAP[m.bizType] ?? m.bizType} · {activityNameOf(m.activityId)}
                      {m.tag ? ` · #${m.tag}` : ''} · {dayjs(m.createdAt).format('YYYY-MM-DD')}
                    </div>
                  </div>
                  {m.fileId && (
                    <Button type="link" size="small" icon={<DownloadOutlined />} onClick={() => downloadFile(m.fileId as number, m.name)}>
                      下载
                    </Button>
                  )}
                  <Popconfirm title="确认删除该材料？" onConfirm={() => handleDelete(m.id)} okText="删除" cancelText="取消">
                    <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                      删除
                    </Button>
                  </Popconfirm>
                </div>
              ))
            )}
          </GlassCard>
        ) : (
          <GlassCard style={{ padding: 16 }}>
            {treeData.length === 0 ? (
              <Empty description="暂无归档材料" />
            ) : (
              <Tree
                showLine
                defaultExpandAll
                blockNode
                treeData={treeData}
              />
            )}
          </GlassCard>
        )}
      </Spin>

      <GlassModal
        title="上传材料"
        open={uploadOpen}
        onCancel={() => setUploadOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setUploadOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" preserve={false} initialValues={{ bizType: 'OTHER' }}>
          <Form.Item name="name" label="材料名称" rules={[{ required: true, message: '请输入材料名称' }]}>
            <Input maxLength={150} placeholder="如 第X次党日活动照片" />
          </Form.Item>
          <Form.Item name="bizType" label="业务类型" rules={[{ required: true, message: '请选择业务类型' }]}>
            <Select options={MATERIAL_BIZ_TYPE_OPTIONS} placeholder="策划书 / 签到表 / 照片…" />
          </Form.Item>
          <Form.Item name="activityId" label="关联活动">
            <Select
              allowClear
              showSearch
              optionFilterProp="label"
              options={activityOptions}
              placeholder="选择关联活动（可选）"
            />
          </Form.Item>
          <Form.Item name="tag" label="标签">
            <Input maxLength={200} placeholder="如 12月26日 / 党日活动（可选）" />
          </Form.Item>
          <Form.Item label="文件" required>
            <UploadFile onUploaded={(id) => setFileId(id)} />
          </Form.Item>
        </Form>
      </GlassModal>
    </div>
  )
}
