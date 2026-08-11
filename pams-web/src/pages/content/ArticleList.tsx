import { useCallback, useEffect, useState } from 'react'
import { App, Button, Form, Input, Popconfirm, Select, Space, Typography } from 'antd'
import {
  AuditOutlined,
  CheckOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  SendOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import StatusTag from '@/components/glass/StatusTag'
import {
  listArticles,
  createArticle,
  updateArticle,
  submitArticle,
  reviewArticle,
  deleteArticle,
  ARTICLE_STATUS_OPTIONS,
  ARTICLE_TYPE_MAP,
  ARTICLE_TYPE_OPTIONS,
  type ArticleSave,
  type ArticleVO,
} from '@/api/article'
import { listUsers, type UserVO } from '@/api/user'
import { useAuthStore } from '@/stores/auth'

type ArticleRecord = ArticleVO & { key: number }

interface ArticleFormValues {
  title: string
  summary?: string
  content?: string
  coverUrl?: string
  articleType: string
}

interface ReviewFormValues {
  comment?: string
}

export default function ArticleList() {
  const { message } = App.useApp()
  const user = useAuthStore((s) => s.user)
  // 审核按钮对齐后端 @PreAuthorize("hasRole('MEDIA_LEADER') or hasAnyRole('TEACHER','DIRECTOR')")
  const canReview = user?.roleCode === 'MEDIA_LEADER' || (user?.roleLevel ?? 0) >= 4

  const [data, setData] = useState<ArticleRecord[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string | undefined>()
  const [type, setType] = useState<string | undefined>()
  const [users, setUsers] = useState<UserVO[]>([])

  const [editorOpen, setEditorOpen] = useState(false)
  const [editing, setEditing] = useState<ArticleVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<ArticleFormValues>()

  const [preview, setPreview] = useState<ArticleVO | null>(null)
  const [reviewTarget, setReviewTarget] = useState<ArticleVO | null>(null)
  const [reviewing, setReviewing] = useState(false)
  const [reviewForm] = Form.useForm<ReviewFormValues>()

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const res = await listArticles({ status, type, keyword: keyword || undefined, page, size })
      setData((res.records ?? []).map((r) => ({ ...r, key: r.id })))
      setTotal(res.total)
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [status, type, keyword, page, size])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  // 作者姓名映射：任务清单作者列为姓名，需拉取用户列表解析 authorId
  useEffect(() => {
    listUsers({ size: 1000 })
      .then((res) => setUsers(res.records ?? []))
      .catch(() => {
        /* http 拦截已提示（干事无 /users 权限时留空，仅显示 ID） */
      })
  }, [])

  const userNameOf = (id: number | null): string => {
    if (id == null) return '-'
    return users.find((u) => u.id === id)?.realName ?? `#${id}`
  }

  const openCreate = () => {
    setEditing(null)
    form.resetFields()
    setEditorOpen(true)
  }

  const openEdit = (record: ArticleVO) => {
    setEditing(record)
    setEditorOpen(true)
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload: ArticleSave = {
        title: values.title.trim(),
        summary: values.summary?.trim() || undefined,
        content: values.content?.trim() || undefined,
        coverUrl: values.coverUrl?.trim() || undefined,
        articleType: values.articleType,
      }
      if (editing) {
        await updateArticle(editing.id, payload)
        message.success('保存成功')
      } else {
        await createArticle(payload)
        message.success('草稿已创建')
        setPage(1)
      }
      setEditorOpen(false)
      fetchList()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleSubmit = async (record: ArticleVO) => {
    try {
      await submitArticle(record.id)
      message.success('已提交审核')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleReview = async (approved: boolean) => {
    if (!reviewTarget) return
    let values: ReviewFormValues = {}
    try {
      values = await reviewForm.validateFields()
    } catch {
      return
    }
    setReviewing(true)
    try {
      await reviewArticle(reviewTarget.id, approved, values.comment?.trim() || undefined)
      message.success(approved ? '审核通过，已发布' : '已驳回')
      setReviewTarget(null)
      reviewForm.resetFields()
      fetchList()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setReviewing(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteArticle(id)
      message.success('已删除')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const columns = [
    { title: '标题', dataIndex: 'title', key: 'title', ellipsis: true },
    { title: '类型', dataIndex: 'articleType', key: 'articleType', render: (t: string) => ARTICLE_TYPE_MAP[t] ?? t },
    { title: '状态', dataIndex: 'status', key: 'status', render: (s: string) => <StatusTag status={s} /> },
    {
      title: '作者',
      key: 'author',
      render: (_: unknown, r: ArticleRecord) => userNameOf(r.authorId),
    },
    {
      title: '发布时间',
      key: 'publishTime',
      width: 170,
      render: (_: unknown, r: ArticleRecord) =>
        r.publishTime ? dayjs(r.publishTime).format('YYYY-MM-DD HH:mm') : '-',
    },
    {
      title: '操作',
      key: 'action',
      width: 220,
      render: (_: unknown, r: ArticleRecord) => (
        <Space size="small" wrap>
          <Button type="link" size="small" icon={<EyeOutlined />} onClick={() => setPreview(r)}>
            预览
          </Button>
          {(r.status === 'DRAFT' || r.status === 'REJECTED') && (
            <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(r)}>
              {r.status === 'REJECTED' ? '改后重提' : '编辑'}
            </Button>
          )}
          {r.status === 'DRAFT' && (
            <Button type="link" size="small" icon={<SendOutlined />} onClick={() => handleSubmit(r)}>
              提交
            </Button>
          )}
          {r.status === 'REJECTED' && (
            <Button type="link" size="small" icon={<SendOutlined />} onClick={() => handleSubmit(r)}>
              提交
            </Button>
          )}
          {r.status === 'PENDING' && canReview && (
            <Button type="link" size="small" icon={<AuditOutlined />} onClick={() => setReviewTarget(r)}>
              审核
            </Button>
          )}
          {(r.status === 'DRAFT' || r.status === 'REJECTED') && (
            <Popconfirm title="确认删除该推文？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
              <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                删除
              </Button>
            </Popconfirm>
          )}
        </Space>
      ),
    },
  ]

  return (
    <div>
      <PageHeader
        title="推文管理"
        description="新媒体中心撰写推送：活动预热 / 活动报道 / 宣传视频"
        extra={
          <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
            撰写推文
          </Button>
        }
      />

      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <Input.Search
            placeholder="搜索标题 / 摘要"
            allowClear
            style={{ width: 240 }}
            onSearch={(v) => {
              setKeyword(v)
              setPage(1)
            }}
          />
          <Select
            placeholder="状态筛选"
            allowClear
            options={ARTICLE_STATUS_OPTIONS}
            style={{ width: 130 }}
            value={status}
            onChange={(v) => {
              setStatus(v)
              setPage(1)
            }}
          />
          <Select
            placeholder="类型筛选"
            allowClear
            options={ARTICLE_TYPE_OPTIONS}
            style={{ width: 130 }}
            value={type}
            onChange={(v) => {
              setType(v)
              setPage(1)
            }}
          />
        </Space>
      </GlassCard>

      <GlassTable<ArticleRecord>
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
      <GlassModal
        title={editing ? '编辑推文' : '撰写推文'}
        open={editorOpen}
        onCancel={() => setEditorOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setEditorOpen(false)}>取消</Button>
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
                  articleType: editing.articleType,
                  summary: editing.summary ?? undefined,
                  content: editing.content ?? undefined,
                  coverUrl: editing.coverUrl || undefined,
                }
              : { articleType: 'REPORT' }
          }
        >
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入推文标题' }]}>
            <Input maxLength={150} placeholder="推文标题" />
          </Form.Item>
          <Form.Item name="articleType" label="类型" rules={[{ required: true, message: '请选择类型' }]}>
            <Select options={ARTICLE_TYPE_OPTIONS} placeholder="预热 / 报道 / 宣传视频" />
          </Form.Item>
          <Form.Item name="summary" label="摘要">
            <Input.TextArea rows={3} maxLength={500} placeholder="一句话摘要，列表与预览中展示" />
          </Form.Item>
          <Form.Item name="content" label="正文" rules={[{ required: true, message: '请输入正文' }]}>
            <Input.TextArea rows={12} maxLength={20000} placeholder="推文正文（支持换行，预览按原文换行展示）" />
          </Form.Item>
          <Form.Item name="coverUrl" label="封面 URL">
            <Input maxLength={255} placeholder="https:// 封面图片地址（可选）" />
          </Form.Item>
        </Form>
      </GlassModal>

      {/* 审核 */}
      <GlassModal
        title="审核推文"
        open={!!reviewTarget}
        onCancel={() => {
          setReviewTarget(null)
          reviewForm.resetFields()
        }}
        footer={
          <Space>
            <Button
              danger
              loading={reviewing}
              onClick={() => handleReview(false)}
            >
              驳回
            </Button>
            <Button type="primary" loading={reviewing} icon={<CheckOutlined />} onClick={() => handleReview(true)}>
              通过并发布
            </Button>
          </Space>
        }
      >
        {reviewTarget && (
          <>
            <Typography.Title level={5} style={{ marginTop: 0 }}>
              {reviewTarget.title}
            </Typography.Title>
            <Typography.Paragraph type="secondary" style={{ whiteSpace: 'pre-wrap' }}>
              {reviewTarget.summary}
            </Typography.Paragraph>
            <div style={{ maxHeight: 320, overflow: 'auto', marginBottom: 16 }}>
              <Typography.Paragraph style={{ whiteSpace: 'pre-wrap' }}>
                {reviewTarget.content}
              </Typography.Paragraph>
            </div>
            <Form form={reviewForm} layout="vertical" preserve={false}>
              <Form.Item name="comment" label="审核意见">
                <Input.TextArea rows={3} maxLength={500} placeholder="选填，驳回时建议填写原因" />
              </Form.Item>
            </Form>
          </>
        )}
      </GlassModal>

      {/* 预览 */}
      <GlassModal
        title="推文预览"
        open={!!preview}
        onCancel={() => setPreview(null)}
        footer={
          <Button onClick={() => setPreview(null)}>关闭</Button>
        }
      >
        {preview && (
          <>
            <div style={{ marginBottom: 8 }}>
              <StatusTag status={preview.status} />
              <span style={{ marginLeft: 8, color: 'var(--color-text-secondary)', fontSize: 12 }}>
                {ARTICLE_TYPE_MAP[preview.articleType] ?? preview.articleType} · {userNameOf(preview.authorId)}
                {preview.publishTime ? ` · ${dayjs(preview.publishTime).format('YYYY-MM-DD HH:mm')}` : ''}
              </span>
            </div>
            <Typography.Title level={4} style={{ marginTop: 0, marginBottom: 8 }}>
              {preview.title}
            </Typography.Title>
            {preview.coverUrl && (
              <div style={{ marginBottom: 12 }}>
                <img src={preview.coverUrl} alt="封面" style={{ maxWidth: '100%', borderRadius: 8 }} onError={(e) => { (e.target as HTMLImageElement).style.display = 'none' }} />
              </div>
            )}
            {preview.summary && (
              <Typography.Paragraph type="secondary" style={{ whiteSpace: 'pre-wrap', marginBottom: 8 }}>
                {preview.summary}
              </Typography.Paragraph>
            )}
            <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
              {preview.content}
            </Typography.Paragraph>
          </>
        )}
      </GlassModal>
    </div>
  )
}
