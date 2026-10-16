import FormSurface, { FormSection } from '@/components/form/FormSurface'
import ArticleDocument from '@/components/records/ArticleDocument'
import { RecordsName, RecordsSummary } from '@/components/records/RecordsLayout'
import { RecordsNav } from '@/components/records/RecordsLayout'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  App,
  Button,
  DatePicker,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Select,
  Space,
  Typography,
} from 'antd'
import {
  AuditOutlined,
  BarChartOutlined,
  CheckOutlined,
  DeleteOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
  SendOutlined,
} from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import LongImageUpload from '@/components/glass/LongImageUpload'
import PageHeader from '@/components/glass/PageHeader'
import StatusTag from '@/components/glass/StatusTag'
import {
  listArticles,
  createArticle,
  updateArticle,
  submitArticle,
  reviewArticle,
  publishArticle,
  updateArticleStats,
  deleteArticle,
  ARTICLE_STATUS_OPTIONS,
  ARTICLE_TYPE_MAP,
  ARTICLE_TYPE_OPTIONS,
  type ArticleSave,
  type ArticleVO,
} from '@/api/article'
import { listActivities, type ActivityVO } from '@/api/activity'
import { listUsers, type UserVO } from '@/api/user'
import { useAuthStore } from '@/stores/auth'

type ArticleRecord = ArticleVO & { key: number }

interface ArticleFormValues {
  title: string
  summary?: string
  content?: string
  coverUrl?: string
  articleType: string
  activityId: number
  authorId: number
  deadline: Dayjs
  imageUrls?: string[]
}

interface ReviewFormValues {
  comment?: string
}

export default function ArticleList() {
  const { message } = App.useApp()
  const user = useAuthStore((s) => s.user)
  // 审核按钮对齐后端 @PreAuthorize("hasRole('MEDIA_LEADER') or hasAnyRole('TEACHER','DIRECTOR')")
  const canReview = (user?.permissions ?? []).includes('content:manage')

  const [data, setData] = useState<ArticleRecord[]>([])
  const [total, setTotal] = useState(0)
  const [loading, setLoading] = useState(false)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [keyword, setKeyword] = useState('')
  const [status, setStatus] = useState<string | undefined>()
  const [type, setType] = useState<string | undefined>()
  const [activityId, setActivityId] = useState<number | undefined>()
  const [users, setUsers] = useState<UserVO[]>([])
  const [activities, setActivities] = useState<ActivityVO[]>([])
  const [overview, setOverview] = useState<number[] | null>(null)

  const [editorOpen, setEditorOpen] = useState(false)
  const [editing, setEditing] = useState<ArticleVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<ArticleFormValues>()

  const [preview, setPreview] = useState<ArticleVO | null>(null)
  const [reviewTarget, setReviewTarget] = useState<ArticleVO | null>(null)
  const [reviewing, setReviewing] = useState(false)
  const [reviewForm] = Form.useForm<ReviewFormValues>()

  const [publishTarget, setPublishTarget] = useState<ArticleVO | null>(null)
  const [publishing, setPublishing] = useState(false)
  const [publishForm] = Form.useForm<{ wxUrl: string }>()

  const [statsTarget, setStatsTarget] = useState<ArticleVO | null>(null)
  const [updating, setUpdating] = useState(false)
  const [statsForm] = Form.useForm<{ readCount: number; likeCount: number }>()

  /** 递增计数器，用于忽略过期异步响应 */
  const fetchIdRef = useRef(0)

  const fetchList = useCallback(async () => {
    const id = ++fetchIdRef.current
    setLoading(true)
    try {
      const res = await listArticles({
        status,
        type,
        keyword: keyword || undefined,
        activityId,
        page,
        size,
      })
      if (id !== fetchIdRef.current) return // 已过期，忽略
      setData((res.records ?? []).map((r) => ({ ...r, key: r.id })))
      setTotal(res.total)
    } catch {
      if (id !== fetchIdRef.current) return
      /* http 拦截已提示 */
    } finally {
      if (id === fetchIdRef.current) setLoading(false)
    }
  }, [status, type, keyword, activityId, page, size])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  useEffect(() => {
    let live = true
    Promise.all(
      [undefined, 'DRAFT', 'PENDING', 'APPROVED'].map((status) =>
        listArticles({ status, page: 1, size: 1 }).then((r) => r.total),
      ),
    )
      .then((counts) => {
        if (live) setOverview(counts)
      })
      .catch(() => {})
    return () => {
      live = false
    }
  }, [data])

  // 作者姓名映射：任务清单作者列为姓名，需拉取用户列表解析 authorId
  useEffect(() => {
    listUsers({ size: 1000 })
      .then((res) => setUsers(res.records ?? []))
      .catch(() => {
        /* http 拦截已提示（干事无 /users 权限时留空，仅显示 ID） */
      })
  }, [])

  // 活动下拉：聚合视图「按活动」筛选 + 撰写表单必填 activityId
  useEffect(() => {
    listActivities({ page: 1, size: 1000 })
      .then((res) => setActivities(res.records ?? []))
      .catch(() => {
        /* http 拦截已提示 */
      })
  }, [])

  const userNameOf = (id: number | null): string => {
    if (id == null) return '-'
    return users.find((u) => u.id === id)?.realName ?? `#${id}`
  }

  const isOverdue = (r: ArticleVO): boolean => {
    if (r.status === 'PUBLISHED' || !r.deadline) return false
    return dayjs(r.deadline).isBefore(dayjs())
  }

  const openCreate = () => {
    setEditing(null)
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
        activityId: values.activityId,
        authorId: values.authorId,
        deadline: dayjs(values.deadline).format('YYYY-MM-DDTHH:mm:ss'),
        imageUrls: values.imageUrls ?? [],
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
    if (!approved && !values.comment?.trim()) {
      reviewForm.setFields([{ name: 'comment', errors: ['请填写驳回原因'] }])
      return
    }
    setReviewing(true)
    try {
      await reviewArticle(
        reviewTarget.id,
        approved,
        values.comment?.trim() || undefined,
      )
      message.success(approved ? '审核通过，待发布' : '已驳回')
      setReviewTarget(null)
      reviewForm.resetFields()
      fetchList()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setReviewing(false)
    }
  }

  const handlePublish = async () => {
    if (!publishTarget) return
    let values: { wxUrl: string }
    try {
      values = await publishForm.validateFields()
    } catch {
      return
    }
    setPublishing(true)
    try {
      await publishArticle(publishTarget.id, { wxUrl: values.wxUrl.trim() })
      message.success('已标记发布')
      setPublishTarget(null)
      publishForm.resetFields()
      fetchList()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setPublishing(false)
    }
  }

  const handleStats = async () => {
    if (!statsTarget) return
    let values: { readCount: number; likeCount: number }
    try {
      values = await statsForm.validateFields()
    } catch {
      return
    }
    setUpdating(true)
    try {
      await updateArticleStats(statsTarget.id, {
        readCount: values.readCount ?? 0,
        likeCount: values.likeCount ?? 0,
      })
      message.success('数据已更新')
      setStatsTarget(null)
      statsForm.resetFields()
      fetchList()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setUpdating(false)
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
    {
      title: '稿件',
      key: 'title',
      width: 320,
      render: (_: unknown, r: ArticleRecord) => (
        <RecordsName
          name={r.title}
          meta={
            (ARTICLE_TYPE_MAP[r.articleType] ?? r.articleType) +
            ' / ' +
            (r.activityName || '未关联活动')
          }
        />
      ),
    },
    {
      title: '状态',
      dataIndex: 'status',
      width: 100,
      render: (s: string) => <StatusTag status={s} />,
    },
    {
      title: '作者',
      width: 110,
      render: (_: unknown, r: ArticleRecord) => userNameOf(r.authorId),
    },
    {
      title: '截止日期',
      width: 120,
      render: (_: unknown, r: ArticleRecord) => (
        <span style={{ color: isOverdue(r) ? 'var(--danger)' : undefined }}>
          {r.deadline ? dayjs(r.deadline).format('YYYY-MM-DD') : '未设置'}
        </span>
      ),
    },
    {
      title: '操作',
      key: 'action',
      align: 'right' as const,
      width: 260,
      render: (_: unknown, r: ArticleRecord) => (
        <Space size="small" wrap>
          <Button
            type="link"
            size="small"
            icon={<EyeOutlined />}
            onClick={() => setPreview(r)}
          >
            预览
          </Button>
          {(r.status === 'DRAFT' || r.status === 'REJECTED') &&
            (canReview || r.authorId === user?.id) && (
              <Button
                type="link"
                size="small"
                icon={<EditOutlined />}
                onClick={() => openEdit(r)}
              >
                {r.status === 'REJECTED' ? '改后重提' : '编辑'}
              </Button>
            )}
          {(r.status === 'DRAFT' || r.status === 'REJECTED') &&
            (canReview || r.authorId === user?.id) && (
              <Button
                type="link"
                size="small"
                icon={<SendOutlined />}
                onClick={() => handleSubmit(r)}
              >
                提交
              </Button>
            )}
          {r.status === 'PENDING' && canReview && (
            <Button
              type="link"
              size="small"
              icon={<AuditOutlined />}
              onClick={() => setReviewTarget(r)}
            >
              审核
            </Button>
          )}
          {r.status === 'APPROVED' &&
            (canReview || r.authorId === user?.id) && (
              <Button
                type="link"
                size="small"
                icon={<SendOutlined />}
                onClick={() => setPublishTarget(r)}
              >
                标记发布
              </Button>
            )}
          {r.status === 'PUBLISHED' &&
            (canReview || r.authorId === user?.id) && (
              <Button
                type="link"
                size="small"
                icon={<BarChartOutlined />}
                onClick={() => setStatsTarget(r)}
              >
                更新数据
              </Button>
            )}
          {(r.status === 'DRAFT' || r.status === 'REJECTED') &&
            (canReview || r.authorId === user?.id) && (
              <Popconfirm
                title="确认删除该推文？"
                onConfirm={() => handleDelete(r.id)}
                okText="删除"
                cancelText="取消"
              >
                <Button
                  type="link"
                  size="small"
                  danger
                  icon={<DeleteOutlined />}
                >
                  删除
                </Button>
              </Popconfirm>
            )}
        </Space>
      ),
    },
  ]

  if (editorOpen)
    return (
      <FormSurface
        mode="workspace"
        description="先确定活动与负责人，再补充底稿、摘要和排版长图。"
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
          key={editing ? `article-edit-${editing.id}` : 'article-create'}
          initialValues={
            editing
              ? {
                  title: editing.title,
                  articleType: editing.articleType,
                  activityId: editing.activityId ?? undefined,
                  authorId: editing.authorId ?? undefined,
                  deadline: editing.deadline
                    ? dayjs(editing.deadline)
                    : undefined,
                  summary: editing.summary ?? undefined,
                  content: editing.content ?? undefined,
                  coverUrl: editing.coverUrl || undefined,
                  imageUrls: editing.imageUrls ?? [],
                }
              : { articleType: 'REPORT', deadline: dayjs().endOf('day') }
          }
        >
          <FormSection title="稿件信息">
            <Form.Item
              className="form-field-wide"
              name="title"
              label="标题"
              rules={[{ required: true, message: '请输入推文标题' }]}
            >
              <Input maxLength={150} placeholder="推文标题" />
            </Form.Item>
            <Form.Item
              name="activityId"
              label="所属活动"
              rules={[{ required: true, message: '请选择所属活动' }]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                options={activities.map((a) => ({
                  value: a.id,
                  label: a.name,
                }))}
                placeholder="选择活动"
              />
            </Form.Item>
            <Form.Item
              name="articleType"
              label="类型"
              rules={[{ required: true, message: '请选择类型' }]}
            >
              <Select
                options={ARTICLE_TYPE_OPTIONS}
                placeholder="预热 / 报道 / 宣传视频"
              />
            </Form.Item>
            <Form.Item
              name="authorId"
              label="负责人"
              rules={[{ required: true, message: '请指定负责人' }]}
            >
              <Select
                showSearch
                optionFilterProp="label"
                options={users.map((u) => ({ value: u.id, label: u.realName }))}
                placeholder="选择负责人"
                disabled={!!editing && !canReview}
              />
            </Form.Item>
            <Form.Item
              name="deadline"
              label="截止时间"
              rules={[{ required: true, message: '请设置截止时间' }]}
            >
              <DatePicker showTime style={{ width: '100%' }} />
            </Form.Item>
          </FormSection>
          <FormSection title="摘要与底稿">
            <Form.Item name="summary" label="摘要">
              <Input.TextArea
                rows={3}
                maxLength={500}
                placeholder="一句话摘要，列表与预览中展示"
              />
            </Form.Item>
            <Form.Item
              name="content"
              label="正文（可选底稿）"
              rules={[{ required: false }]}
            >
              <Input.TextArea
                rows={8}
                maxLength={20000}
                placeholder="可选文字底稿；排版以秀米长图截图为准"
              />
            </Form.Item>
          </FormSection>
          <FormSection title="排版与封面">
            <Form.Item
              className="form-field-wide"
              name="imageUrls"
              label="长图截图"
            >
              <LongImageUpload />
            </Form.Item>
            <Form.Item
              className="form-field-wide"
              name="coverUrl"
              label="封面 URL"
            >
              <Input
                maxLength={255}
                placeholder="https:// 封面图片地址（可选）"
              />
            </Form.Item>
          </FormSection>
        </Form>
      </FormSurface>
    )

  if (!!reviewTarget)
    return (
      <FormSurface
        mode="workspace"
        description="对照稿件内容填写审核意见，完成后通过或驳回。"
        title="稿件审阅"
        width={1120}
        className="records-review-modal"
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
            <Button
              type="primary"
              loading={reviewing}
              icon={<CheckOutlined />}
              onClick={() => handleReview(true)}
            >
              通过（待发布）
            </Button>
          </Space>
        }
      >
        {reviewTarget && (
          <div className="records-two">
            <ArticleDocument
              article={reviewTarget}
              author={userNameOf(reviewTarget.authorId)}
            />
            <aside className="records-panel">
              <h3>审核意见</h3>
              <p className="records-note">
                请核对标题、活动名称、日期与正文信息，并检查长图中的文字。
              </p>
              <Form form={reviewForm} layout="vertical" preserve={false}>
                <Form.Item name="comment" label="审核意见">
                  <Input.TextArea
                    rows={5}
                    maxLength={500}
                    placeholder="驳回时必须说明修改要求"
                    onChange={() =>
                      reviewForm.setFields([{ name: 'comment', errors: [] }])
                    }
                  />
                </Form.Item>
              </Form>
            </aside>
          </div>
        )}
      </FormSurface>
    )

  return (
    <div className="records-page">
      <PageHeader
        title="内容宣传"
        description="围绕活动准备宣传内容，跟进审稿、发布与传播记录。"
        extra={
          canReview ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              撰写推文
            </Button>
          ) : null
        }
      />
      <RecordsNav section="content" />
      <RecordsSummary
        items={['全部推文', '草稿', '待审', '待发布'].map((label, i) => ({
          label,
          value: overview?.[i] ?? '—',
          note: '篇',
        }))}
      />
      <div className="records-flow">
        <AuditOutlined />
        <div>
          <strong>先审核，再登记发布</strong>
          <p>草稿 → 待审 → 待发布 → 已发布；驳回后修改重提。</p>
        </div>
      </div>

      <GlassCard
        className="records-toolbar"
        style={{ padding: 16, marginBottom: 16 }}
      >
        <Space wrap>
          <Input.Search
            placeholder="搜索标题 / 摘要"
            allowClear
            style={{ width: 200 }}
            onSearch={(v) => {
              setKeyword(v)
              setPage(1)
            }}
          />
          <Select
            placeholder="按活动"
            allowClear
            showSearch
            optionFilterProp="label"
            options={activities.map((a) => ({ value: a.id, label: a.name }))}
            style={{ width: 200 }}
            value={activityId}
            onChange={(v) => {
              setActivityId(v)
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

      {/* 审核 */}

      {/* 标记发布 */}
      <FormSurface
        mode="dialog"
        title="标记发布"
        open={!!publishTarget}
        onCancel={() => {
          setPublishTarget(null)
          publishForm.resetFields()
        }}
        footer={
          <Space>
            <Button onClick={() => setPublishTarget(null)}>取消</Button>
            <Button type="primary" loading={publishing} onClick={handlePublish}>
              确认发布
            </Button>
          </Space>
        }
      >
        {publishTarget && (
          <>
            <Typography.Paragraph type="secondary">
              「{publishTarget.title}
              」已通过审核，填公众号发布链接后完成发布归档。
            </Typography.Paragraph>
            <Form form={publishForm} layout="vertical" preserve={false}>
              <Form.Item
                name="wxUrl"
                label="公众号链接"
                rules={[{ required: true, message: '请输入公众号链接' }]}
              >
                <Input
                  maxLength={500}
                  placeholder="https://mp.weixin.qq.com/s/..."
                />
              </Form.Item>
            </Form>
          </>
        )}
      </FormSurface>

      {/* 更新数据 */}
      <FormSurface
        mode="dialog"
        title="更新阅读数据"
        open={!!statsTarget}
        onCancel={() => {
          setStatsTarget(null)
          statsForm.resetFields()
        }}
        footer={
          <Space>
            <Button onClick={() => setStatsTarget(null)}>取消</Button>
            <Button type="primary" loading={updating} onClick={handleStats}>
              保存
            </Button>
          </Space>
        }
      >
        {statsTarget && (
          <Form
            form={statsForm}
            layout="vertical"
            preserve={false}
            initialValues={{
              readCount: statsTarget.readCount ?? 0,
              likeCount: statsTarget.likeCount ?? 0,
            }}
          >
            <Form.Item
              name="readCount"
              label="阅读量"
              rules={[{ required: true, message: '请输入阅读量' }]}
            >
              <InputNumber min={0} precision={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item
              name="likeCount"
              label="在看数"
              rules={[{ required: true, message: '请输入在看数' }]}
            >
              <InputNumber min={0} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          </Form>
        )}
      </FormSurface>

      {/* 文档工作面保留长图、正文、发布与活动关联 */}
      <GlassModal
        title="稿件详情"
        width={1000}
        className="records-review-modal"
        open={!!preview}
        onCancel={() => setPreview(null)}
        footer={<Button onClick={() => setPreview(null)}>关闭</Button>}
      >
        {preview && (
          <ArticleDocument
            article={preview}
            author={userNameOf(preview.authorId)}
          />
        )}
      </GlassModal>
    </div>
  )
}
