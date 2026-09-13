import { useCallback, useEffect, useMemo, useState } from 'react'
import { App, Button, Form, Input, Popconfirm, Select, Space, Tabs } from 'antd'
import { DeleteOutlined, DownloadOutlined, EyeOutlined, PlusOutlined, ThunderboltOutlined } from '@ant-design/icons'
import { useNavigate } from 'react-router-dom'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import UploadFile from '@/components/glass/UploadFile'
import FilePreviewModal from '@/components/file/FilePreviewModal'
import { useHasPerm } from '@/components/glass/RequireRole'
import {
  createTemplate,
  deleteTemplate,
  listTemplates,
  TEMPLATE_CATEGORIES,
  TEMPLATE_CATEGORY_MAP,
  TEMPLATE_CATEGORY_OPTIONS,
  type TemplateVO,
} from '@/api/template'
import { downloadFile } from '@/api/file'
import { listUsers, type UserVO } from '@/api/user'

interface TemplateFormValues {
  name: string
  category: string
  description?: string
}

export default function TemplateList() {
  const { message } = App.useApp()
  const navigate = useNavigate()
  const hasPerm = useHasPerm()
  const [all, setAll] = useState<TemplateVO[]>([])
  const [loading, setLoading] = useState(false)
  const [category, setCategory] = useState<string>()
  const [users, setUsers] = useState<UserVO[]>([])
  const [uploadOpen, setUploadOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [fileId, setFileId] = useState<number | null>(null)
  const [preview, setPreview] = useState<TemplateVO | null>(null)
  const [form] = Form.useForm<TemplateFormValues>()

  // 一次拉全量（模板数量级小），前端按分类筛选，Tabs 计数始终准确
  const fetchList = useCallback(() => {
    setLoading(true)
    listTemplates()
      .then((res) => setAll(res ?? []))
      .catch(() => {
        /* http 拦截已提示 */
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  const data = useMemo(
    () => (category ? all.filter((t) => t.category === category) : all),
    [all, category],
  )

  // 上传人姓名映射（createdBy → realName）
  useEffect(() => {
    listUsers({ size: 1000 })
      .then((res) => setUsers(res.records ?? []))
      .catch(() => {
        /* 干事可能无 /users 权限，显示 #id 兜底 */
      })
  }, [])

  const uploaderNameOf = (id: number | null): string => {
    if (id == null) return '-'
    return users.find((u) => u.id === id)?.realName ?? `#${id}`
  }

  const openUpload = () => {
    setFileId(null)
    form.resetFields()
    setUploadOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    if (!fileId) {
      message.warning('请先上传模板文件')
      return
    }
    setSaving(true)
    try {
      await createTemplate({
        name: values.name.trim(),
        category: values.category,
        description: values.description?.trim() || null,
        fileId,
      })
      message.success('模板已添加')
      setUploadOpen(false)
      fetchList()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteTemplate(id)
      message.success('已删除')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  /** F2「使用此模板」：策划书模板引导到活动策划书的「从模板库导入」；其余类型下载后给出使用指引 */
  const handleUse = async (r: TemplateVO) => {
    if (r.category === 'PLAN') {
      message.info('请打开目标活动 → 策划书 Tab → 编辑 →「从模板库导入」，选中该模板即可自动填充章节')
      navigate('/activities')
      return
    }
    if (!r.fileId) {
      message.warning('该模板未关联文件')
      return
    }
    await downloadFile(r.fileId, r.name)
    const guide: Record<string, string> = {
      SEAT: '模板已下载：可在活动详情座位表 Tab 参考搭建，或直接打印使用',
      AGENDA: '模板已下载：填写后可在活动详情议程 Tab 对照录入',
      SIGNIN: '模板已下载：名单列结构与「应签名单」上传一致，填好即可直接上传',
      NEWS: '模板已下载：按模板结构在「内容宣传 → 新闻稿」撰写',
    }
    message.success(guide[r.category] ?? '模板已下载')
  }

  const columns = [
    {
      title: '名称',
      dataIndex: 'name',
      key: 'name',
      ellipsis: true,
      render: (name: string, r: TemplateVO) =>
        r.fileId ? (
          <Button
            type="link"
            size="small"
            className="template-name-link"
            style={{ padding: 0, height: 'auto' }}
            onClick={() => setPreview(r)}
          >
            {name}
          </Button>
        ) : (
          name
        ),
    },
    {
      title: '分类',
      dataIndex: 'category',
      key: 'category',
      render: (c: string) => TEMPLATE_CATEGORY_MAP[c] ?? c,
    },
    {
      title: '上传人',
      key: 'createdBy',
      width: 120,
      render: (_: unknown, r: TemplateVO) => uploaderNameOf(r.createdBy),
    },
    {
      title: '上传时间',
      dataIndex: 'createdAt',
      key: 'createdAt',
      width: 170,
      render: (t: string) => (t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '操作',
      key: 'action',
      width: 220,
      render: (_: unknown, r: TemplateVO) => (
        <Space size="small" wrap>
          {r.fileId && (
            <Button type="link" size="small" icon={<ThunderboltOutlined />} onClick={() => handleUse(r)}>
              使用
            </Button>
          )}
          {r.fileId && (
            <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => setPreview(r)}>
              预览
            </Button>
          )}
          {r.fileId && (
            <Button type="link" size="small" icon={<DownloadOutlined />} onClick={() => downloadFile(r.fileId as number, r.name)}>
              下载
            </Button>
          )}
          {hasPerm('template:manage') && (
          <Popconfirm title="确认删除该模板？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
            <Button type="link" size="small" danger icon={<DeleteOutlined />}>
              删除
            </Button>
          </Popconfirm>
        )}
        </Space>
      ),
    },
  ]

  const tabItems = TEMPLATE_CATEGORIES.map((c) => ({
    key: c,
    label: `${TEMPLATE_CATEGORY_MAP[c] ?? c}（${all.filter((t) => t.category === c).length}）`,
    children: null,
  }))

  return (
    <div>
      <PageHeader
        title="模板库"
        description="活动常用模板资产：策划书 / 座位表 / 议程表 / 签到表 / 水牌 / LOGO / 党徽 / 新闻稿"
        extra={
          hasPerm('template:manage') && (
          <Button type="primary" icon={<PlusOutlined />} onClick={openUpload}>
            上传模板
          </Button>
        )
        }
      />

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Tabs
          activeKey={category ?? 'ALL'}
          onChange={(key) => setCategory(key === 'ALL' ? undefined : key)}
          items={[
            { key: 'ALL', label: '全部' },
            ...tabItems,
          ]}
        />
      </GlassCard>

      <GlassTable<TemplateVO>
        columns={columns}
        dataSource={data.map((r) => ({ ...r, key: r.id }))}
        rowKey="id"
        loading={loading}
        pagination={false}
        locale={{ emptyText: '暂无模板' }}
      />

      <GlassModal
        title="上传模板"
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
        <Form form={form} layout="vertical" preserve={false} initialValues={{ category: 'PLAN' }}>
          <Form.Item name="name" label="模板名称" rules={[{ required: true, message: '请输入模板名称' }]}>
            <Input maxLength={150} placeholder="如 党日活动策划书模板" />
          </Form.Item>
          <Form.Item name="category" label="分类" rules={[{ required: true, message: '请选择分类' }]}>
            <Select options={TEMPLATE_CATEGORY_OPTIONS} placeholder="策划书 / 座位表 / …" />
          </Form.Item>
          <Form.Item name="description" label="说明">
            <Input.TextArea rows={2} maxLength={500} placeholder="模板用途说明（可选）" />
          </Form.Item>
          <Form.Item label="文件" required>
            <UploadFile onUploaded={(id) => setFileId(id)} />
          </Form.Item>
        </Form>
      </GlassModal>

      {preview?.fileId != null && (
        <FilePreviewModal
          open
          onClose={() => setPreview(null)}
          fileId={preview.fileId}
          fileName={preview.originFilename || preview.name}
        />
      )}
    </div>
  )
}
