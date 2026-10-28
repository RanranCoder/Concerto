import FormSurface, { FormSection } from '@/components/form/FormSurface'
import { Link } from 'react-router-dom'
import { RecordsNav } from '@/components/records/RecordsLayout'
import { useCallback, useEffect, useState } from 'react'
import {
  App,
  Button,
  Form,
  Input,
  Popconfirm,
  Select,
  Space,
  Typography,
} from 'antd'
import { listActivities, type ActivityVO } from '@/api/activity'
import {
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  SendOutlined,
} from '@ant-design/icons'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import StatusTag from '@/components/glass/StatusTag'
import {
  listNews,
  createNews,
  updateNews,
  publishNews,
  deleteNews,
  type NewsSave,
  type NewsVO,
} from '@/api/news'
import { listUsers, type UserVO } from '@/api/user'
import { useAuthStore } from '@/stores/auth'

type NewsRecord = NewsVO & { key: number }

interface NewsFormValues {
  title: string
  subtitle?: string
  content?: string
  activityId?: number
}

export default function NewsList() {
  const { message } = App.useApp()
  const user = useAuthStore((s) => s.user)
  const isMinisterOrAbove = (user?.permissions ?? []).includes('news:manage')

  const [data, setData] = useState<NewsRecord[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [keyword, setKeyword] = useState('')
  const [users, setUsers] = useState<UserVO[]>([])
  const [activities, setActivities] = useState<ActivityVO[]>([])
  useEffect(() => {
    listActivities({ size: 1000 })
      .then((r) => setActivities(r.records ?? []))
      .catch(() => {})
  }, [])

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<NewsVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<NewsFormValues>()
  const [preview, setPreview] = useState<NewsVO | null>(null)

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const res = await listNews({ keyword: keyword || undefined, page, size })
      setData((res.records ?? []).map((r) => ({ ...r, key: r.id })))
      setTotal(res.total)
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [keyword, page, size])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  useEffect(() => {
    listUsers({ size: 1000 })
      .then((res) => setUsers(res.records ?? []))
      .catch(() => {
        /* http 拦截已提示（无 /users 权限时仅显示 ID） */
      })
  }, [])

  const userNameOf = (id: number | null): string => {
    if (id == null) return '-'
    return users.find((u) => u.id === id)?.realName ?? `#${id}`
  }

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    setModalOpen(true)
  }

  const openEdit = (record: NewsVO) => {
    setEditing(record)
    setModalOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload: NewsSave = {
        title: values.title.trim(),
        subtitle: values.subtitle?.trim() || undefined,
        content: values.content?.trim() || undefined,
        activityId: values.activityId ?? null,
      }
      if (editing) {
        await updateNews(editing.id, payload)
        message.success('保存成功')
      } else {
        await createNews(payload)
        message.success('新闻稿已创建')
        setPage(1)
      }
      setModalOpen(false)
      fetchList()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteNews(id)
      message.success('已删除')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handlePublish = async (id: number) => {
    try {
      await publishNews(id)
      message.success('已发布')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const columns = [
    { title: '标题', dataIndex: 'title', key: 'title', ellipsis: true },
    {
      title: '副标题',
      dataIndex: 'subtitle',
      key: 'subtitle',
      ellipsis: true,
      render: (v: string) => v || '-',
    },
    {
      title: '状态',
      dataIndex: 'status',
      key: 'status',
      render: (s: string) => <StatusTag status={s} />,
    },
    {
      title: '作者',
      key: 'author',
      render: (_: unknown, r: NewsRecord) => userNameOf(r.authorId),
    },
    {
      title: '发布日期',
      key: 'publishDate',
      width: 120,
      align: 'right' as const,
      onCell: () => ({ style: { fontVariantNumeric: 'tabular-nums' } }),
      render: (_: unknown, r: NewsRecord) => r.publishDate || '-',
    },
    {
      title: '操作',
      key: 'action',
      align: 'right' as const,
      width: 180,
      render: (_: unknown, r: NewsRecord) => (
        <Space size="small">
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => setPreview(r)}
          >
            预览
          </Button>
          {isMinisterOrAbove && (
            <Button
              type="link"
              size="small"
              icon={<EditOutlined />}
              onClick={() => openEdit(r)}
            >
              编辑
            </Button>
          )}
          {user?.permissions.includes('news:publish') && r.status === 'DRAFT' && (
            <Popconfirm
              title="确认发布该新闻稿？"
              onConfirm={() => handlePublish(r.id)}
              okText="发布"
              cancelText="取消"
            >
              <Button type="link" size="small" icon={<SendOutlined />}>
                发布
              </Button>
            </Popconfirm>
          )}
          {isMinisterOrAbove && (
            <Popconfirm
              title="确认删除该新闻稿？"
              onConfirm={() => handleDelete(r.id)}
              okText="删除"
              cancelText="取消"
            >
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ]

  if (modalOpen)
    return (
      <FormSurface
        mode="workspace"
        description="关联活动后撰写正文，保存后可在列表中预览与发布。"
        title={editing ? '编辑新闻稿' : '撰写新闻稿'}
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setModalOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存
            </Button>
          </Space>
        }
      >
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          initialValues={
            editing
              ? {
                  title: editing.title,
                  subtitle: editing.subtitle ?? undefined,
                  content: editing.content ?? undefined,
                  activityId: editing.activityId ?? undefined,
                }
              : undefined
          }
        >
          <FormSection title="稿件信息">
            <Form.Item
              className="form-field-wide"
              name="title"
              label="标题"
              rules={[{ required: true, message: '请输入新闻稿标题' }]}
            >
              <Input maxLength={150} placeholder="新闻稿标题" />
            </Form.Item>
            <Form.Item
              className="form-field-wide"
              name="subtitle"
              label="副标题"
            >
              <Input maxLength={300} placeholder="副标题（可选）" />
            </Form.Item>
            <Form.Item name="activityId" label="关联活动">
              <Select
                allowClear
                showSearch
                optionFilterProp="label"
                options={activities.map((a) => ({
                  value: a.id,
                  label: a.name,
                }))}
                placeholder="选择活动（可选）"
              />
            </Form.Item>
          </FormSection>
          <FormSection title="新闻正文">
            <Form.Item
              name="content"
              label="正文"
              rules={[{ required: true, message: '请输入正文' }]}
            >
              <Input.TextArea
                rows={12}
                maxLength={20000}
                placeholder="新闻稿正文（支持换行，预览按原文换行展示）"
              />
            </Form.Item>
          </FormSection>
        </Form>
      </FormSurface>
    )

  return (
    <div className="records-page">
      <PageHeader
        title="内容宣传"
        description="独立维护活动新闻稿，保存草稿与登记发布。"
        extra={
          isMinisterOrAbove && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              撰写新闻稿
            </Button>
          )
        }
      />
      <RecordsNav section="content" />
      <div className="records-flow">
        <div>
          <strong>新闻稿独立维护</strong>
          <p>草稿 → 已发布；可关联活动，不套用推文审稿流程。</p>
        </div>
      </div>

      <GlassCard
        className="records-toolbar"
        style={{ padding: 16, marginBottom: 16 }}
      >
        <Space wrap>
          <Input.Search
            placeholder="搜索标题 / 副标题"
            allowClear
            style={{ width: 240 }}
            onSearch={(v) => {
              setKeyword(v)
              setPage(1)
            }}
          />
        </Space>
      </GlassCard>

      <GlassTable<NewsRecord>
        scroll={{ x: 900 }}
        columns={columns}
        dataSource={data}
        rowKey="id"
        loading={loading}
        pagination={{
          current: page,
          pageSize: size,
          total,
          showSizeChanger: true,
          showTotal: (t) => `共 ${t} 条`,
          onChange: (p, s) => {
            setPage(p)
            setSize(s)
          },
        }}
      />

      {/* 撰写 / 编辑 */}

      {/* 预览 */}
      <GlassModal
        title="新闻稿详情"
        width={900}
        className="records-review-modal"
        open={!!preview}
        onCancel={() => setPreview(null)}
        footer={<Button onClick={() => setPreview(null)}>关闭</Button>}
      >
        {preview && (
          <article className="records-paper">
            <div style={{ marginBottom: 8 }}>
              <StatusTag status={preview.status} />
              <span
                style={{
                  marginLeft: 8,
                  color: 'var(--text-secondary)',
                  fontSize: 13,
                }}
              >
                作者：{userNameOf(preview.authorId)}
                {preview.publishDate ? ` · ${preview.publishDate}` : ''}
              </span>
            </div>
            <Typography.Title
              level={4}
              style={{ marginTop: 0, marginBottom: 4 }}
            >
              {preview.title}
            </Typography.Title>
            {preview.subtitle && (
              <Typography.Paragraph
                type="secondary"
                style={{ whiteSpace: 'pre-wrap', marginBottom: 8 }}
              >
                {preview.subtitle}
              </Typography.Paragraph>
            )}
            <Typography.Paragraph
              style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}
            >
              {preview.content}
            </Typography.Paragraph>
            {preview.activityId != null && (
              <div className="records-related">
                <Link to={'/activities/' + preview.activityId}>
                  查看关联活动
                </Link>
              </div>
            )}
          </article>
        )}
      </GlassModal>
    </div>
  )
}
