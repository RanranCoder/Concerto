import { useCallback, useEffect, useState } from 'react'
import {
  Button,
  Col,
  DatePicker,
  Empty,
  Form,
  Input,
  InputNumber,
  message,
  Popconfirm,
  Row,
  Select,
  Space,
  Spin,
  Tag,
  Typography,
  Upload,
} from 'antd'
import {
  AuditOutlined,
  BarChartOutlined,
  CheckOutlined,
  DeleteOutlined,
  EditOutlined,
  PlusOutlined,
  SendOutlined,
  ThunderboltOutlined,
} from '@ant-design/icons'
import dayjs, { type Dayjs } from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import {
  listArticles,
  createArticle,
  updateArticle,
  submitArticle,
  reviewArticle,
  publishArticle,
  updateArticleStats,
  deleteArticle,
  ARTICLE_STATUS_MAP,
  ARTICLE_TYPE_MAP,
  ARTICLE_TYPE_OPTIONS,
  type ArticleSave,
  type ArticleVO,
} from '@/api/article'
import { uploadFile, downloadUrl } from '@/api/file'
import { listUsers, type UserVO } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import type { ActivityVO } from '@/api/activity'

const ARTICLE_STATUS_COLOR: Record<string, string> = {
  DRAFT: 'default',
  PENDING: 'processing',
  APPROVED: 'gold',
  PUBLISHED: 'green',
  REJECTED: 'red',
}

interface ArticleFormValues {
  title: string
  summary?: string
  content?: string
  articleType: string
  authorId: number
  deadline: Dayjs
  imageUrls?: string[]
}

/** 长图上传：手动上传拿 FileRec，回填 /api/files/{id}/download；受控 Form.Item value/onChange */
function LongImageUpload({ value, onChange }: { value?: string[]; onChange?: (urls: string[]) => void }) {
  const list = (value ?? []).map((url, i) => ({
    uid: `long-${i}-${Date.now()}`,
    name: `长图${i + 1}`,
    status: 'done' as const,
    url,
  }))
  return (
    <Upload
      listType="picture-card"
      accept="image/*"
      fileList={list}
      beforeUpload={(file) => {
        uploadFile(file as unknown as File, 'article')
          .then((rec) => {
            onChange?.([...(value ?? []), downloadUrl(rec.id)])
            message.success('长图已上传')
          })
          .catch(() => message.error('长图上传失败'))
        return false
      }}
      onRemove={(file) => {
        onChange?.((value ?? []).filter((u) => u !== file.url))
        return true
      }}
    >
      {(value?.length ?? 0) < 9 ? (
        <div>
          <PlusOutlined />
          <div style={{ marginTop: 8 }}>上传长图</div>
        </div>
      ) : null}
    </Upload>
  )
}

export default function ActivityArticlesTab({
  activityId,
  activity,
}: {
  activityId: number
  activity?: ActivityVO
}) {
  const user = useAuthStore((s) => s.user)
  // 管理/审核权限对齐后端 @PreAuthorize("hasRole('MEDIA_LEADER') or hasAnyRole('TEACHER','DIRECTOR')")
  const canManage = user?.roleCode === 'MEDIA_LEADER' || (user?.roleLevel ?? 0) >= 4

  const [articles, setArticles] = useState<ArticleVO[]>([])
  const [users, setUsers] = useState<UserVO[]>([])
  const [loading, setLoading] = useState(false)

  const [modalOpen, setModalOpen] = useState(false)
  const [editing, setEditing] = useState<ArticleVO | null>(null)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<ArticleFormValues>()

  const [reviewTarget, setReviewTarget] = useState<ArticleVO | null>(null)
  const [reviewing, setReviewing] = useState(false)
  const [reviewForm] = Form.useForm<{ comment?: string }>()

  const [publishTarget, setPublishTarget] = useState<ArticleVO | null>(null)
  const [publishing, setPublishing] = useState(false)
  const [publishForm] = Form.useForm<{ wxUrl: string }>()

  const [statsTarget, setStatsTarget] = useState<ArticleVO | null>(null)
  const [updating, setUpdating] = useState(false)
  const [statsForm] = Form.useForm<{ readCount: number; likeCount: number }>()

  // 长图大图预览
  const [previewTarget, setPreviewTarget] = useState<string | null>(null)

  const fetchList = useCallback(async () => {
    setLoading(true)
    try {
      const res = await listArticles({ activityId, page: 1, size: 1000 })
      setArticles(res.records ?? [])
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [activityId])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  useEffect(() => {
    listUsers({ size: 1000 })
      .then((res) => setUsers(res.records ?? []))
      .catch(() => {
        /* http 拦截已提示（无权限时留空，仅显示 ID） */
      })
  }, [])

  const userNameOf = (id: number | null): string => {
    if (id == null) return '-'
    return users.find((u) => u.id === id)?.realName ?? `#${id}`
  }

  const isOverdue = (a: ArticleVO): boolean => {
    if (a.status === 'PUBLISHED' || !a.deadline) return false
    return dayjs(a.deadline).isBefore(dayjs())
  }

  const openCreate = () => {
    setEditing(null)
    setModalOpen(true)
  }

  const openEdit = (a: ArticleVO) => {
    setEditing(a)
    setModalOpen(true)
  }

  /** 快捷创建：预热默认活动开始前 3 天 / 报道默认结束后 2 天（负责人默认当前用户，可后续编辑改派） */
  const quickCreate = async (type: 'PREHEAT' | 'REPORT') => {
    const base =
      type === 'PREHEAT'
        ? activity?.startDate
          ? dayjs(activity.startDate)
          : dayjs()
        : activity?.endDate
          ? dayjs(activity.endDate)
          : dayjs()
    const deadline = (type === 'PREHEAT' ? base.subtract(3, 'day') : base.add(2, 'day')).format('YYYY-MM-DDTHH:mm:ss')
    const title = `${activity?.name ?? '活动'}${type === 'PREHEAT' ? '预热' : '报道'}推文`
    try {
      await createArticle({ title, articleType: type, activityId, deadline, authorId: user?.id })
      message.success(type === 'PREHEAT' ? '已创建预热推文' : '已创建报道推文')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleSave = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      const payload: ArticleSave = {
        title: values.title.trim(),
        articleType: values.articleType,
        activityId,
        authorId: values.authorId,
        deadline: dayjs(values.deadline).format('YYYY-MM-DDTHH:mm:ss'),
        summary: values.summary?.trim() || undefined,
        content: values.content?.trim() || undefined,
        imageUrls: values.imageUrls ?? [],
      }
      if (editing) {
        await updateArticle(editing.id, payload)
        message.success('推文已更新')
      } else {
        await createArticle(payload)
        message.success('推文已创建')
      }
      setModalOpen(false)
      fetchList()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleSubmit = async (a: ArticleVO) => {
    try {
      await submitArticle(a.id)
      message.success('已提交审核')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const handleReview = async (approved: boolean) => {
    if (!reviewTarget) return
    let values: { comment?: string } = {}
    try {
      values = await reviewForm.validateFields()
    } catch {
      return
    }
    setReviewing(true)
    try {
      await reviewArticle(reviewTarget.id, approved, values.comment?.trim() || undefined)
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
      await updateArticleStats(statsTarget.id, { readCount: values.readCount ?? 0, likeCount: values.likeCount ?? 0 })
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

  return (
    <div>
      <Space style={{ marginBottom: 16 }} wrap>
        {canManage && (
          <>
            <Button type="primary" icon={<PlusOutlined />} onClick={openCreate}>
              新建推文
            </Button>
            <Button icon={<ThunderboltOutlined />} onClick={() => quickCreate('PREHEAT')}>
              快捷创建预热
            </Button>
            <Button icon={<ThunderboltOutlined />} onClick={() => quickCreate('REPORT')}>
              快捷创建报道
            </Button>
          </>
        )}
      </Space>

      <Spin spinning={loading}>
        {articles.length === 0 ? (
          <GlassCard style={{ padding: 40, textAlign: 'center' }}>
            <Empty description="该活动暂无推文任务，可点击「快捷创建预热」生成预热推文" />
          </GlassCard>
        ) : (
          <Row gutter={[16, 16]}>
            {articles.map((a) => (
              <Col key={a.id} xs={24} md={12} xl={8}>
                <GlassCard style={{ padding: 16, height: '100%' }}>
                  <div
                    style={{
                      display: 'flex',
                      justifyContent: 'space-between',
                      gap: 8,
                      marginBottom: 8,
                    }}
                  >
                    <Typography.Text strong ellipsis style={{ flex: 1 }}>
                      {a.title}
                    </Typography.Text>
                    <Space size={4}>
                      <Tag color={ARTICLE_STATUS_COLOR[a.status] ?? 'default'}>
                        {ARTICLE_STATUS_MAP[a.status] ?? a.status}
                      </Tag>
                      <Tag>{ARTICLE_TYPE_MAP[a.articleType] ?? a.articleType}</Tag>
                    </Space>
                  </div>

                  <div style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginBottom: 8 }}>
                    负责人：{userNameOf(a.authorId)} · 截止：
                    <span style={{ color: isOverdue(a) ? 'var(--color-red)' : undefined }}>
                      {a.deadline ? dayjs(a.deadline).format('YYYY-MM-DD HH:mm') : '-'}
                    </span>
                  </div>

                  {a.imageUrls?.length ? (
                    <div style={{ display: 'flex', gap: 8, marginBottom: 8 }}>
                      {a.imageUrls.map((u) => (
                        <img
                          key={u}
                          src={u}
                          alt="长图"
                          onClick={() => setPreviewTarget(u)}
                          style={{ maxWidth: 60, maxHeight: 80, objectFit: 'cover', borderRadius: 4, cursor: 'pointer' }}
                        />
                      ))}
                    </div>
                  ) : null}

                  <div style={{ fontSize: 12, color: 'var(--color-text-secondary)', marginBottom: 8 }}>
                    {a.wxUrl ? (
                      <a href={a.wxUrl} target="_blank" rel="noreferrer">
                        公众号链接
                      </a>
                    ) : (
                      '未发布链接'
                    )}
                    {' · 阅读 '}
                    {a.readCount ?? 0}
                    {' · 在看 '}
                    {a.likeCount ?? 0}
                  </div>

                  <Space size="small" wrap>
                    {(a.status === 'DRAFT' || a.status === 'REJECTED') && (canManage || a.authorId === user?.id) && (
                      <>
                        <Button type="link" size="small" icon={<EditOutlined />} onClick={() => openEdit(a)}>
                          编辑
                        </Button>
                        <Button type="link" size="small" icon={<SendOutlined />} onClick={() => handleSubmit(a)}>
                          提交
                        </Button>
                        <Popconfirm
                          title="确认删除该推文？"
                          onConfirm={() => handleDelete(a.id)}
                          okText="删除"
                          cancelText="取消"
                        >
                          <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                            删除
                          </Button>
                        </Popconfirm>
                      </>
                    )}
                    {a.status === 'PENDING' && canManage && (
                      <Button type="link" size="small" icon={<AuditOutlined />} onClick={() => setReviewTarget(a)}>
                        审核
                      </Button>
                    )}
                    {a.status === 'APPROVED' && (canManage || a.authorId === user?.id) && (
                      <Button type="link" size="small" icon={<SendOutlined />} onClick={() => setPublishTarget(a)}>
                        标记发布
                      </Button>
                    )}
                    {a.status === 'PUBLISHED' && (canManage || a.authorId === user?.id) && (
                      <Button type="link" size="small" icon={<BarChartOutlined />} onClick={() => setStatsTarget(a)}>
                        更新数据
                      </Button>
                    )}
                  </Space>
                </GlassCard>
              </Col>
            ))}
          </Row>
        )}
      </Spin>

      {/* 新建 / 编辑（activityId 固定为当前活动，不显示活动下拉） */}
      <GlassModal
        title={editing ? '编辑推文' : '新建推文'}
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
          key={editing ? `article-edit-${editing.id}` : 'article-create'}
          initialValues={
            editing
              ? {
                  title: editing.title,
                  articleType: editing.articleType,
                  authorId: editing.authorId ?? undefined,
                  deadline: editing.deadline ? dayjs(editing.deadline) : undefined,
                  summary: editing.summary ?? undefined,
                  content: editing.content ?? undefined,
                  imageUrls: editing.imageUrls ?? [],
                }
              : { articleType: 'REPORT', deadline: dayjs().endOf('day') }
          }
        >
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入推文标题' }]}>
            <Input maxLength={150} placeholder="推文标题" />
          </Form.Item>
          <Form.Item name="articleType" label="类型" rules={[{ required: true, message: '请选择类型' }]}>
            <Select options={ARTICLE_TYPE_OPTIONS} placeholder="预热 / 报道 / 宣传视频" />
          </Form.Item>
          <Form.Item name="authorId" label="负责人" rules={[{ required: true, message: '请指定负责人' }]}>
            <Select
              showSearch
              optionFilterProp="label"
              options={users.map((u) => ({ value: u.id, label: u.realName }))}
              placeholder="选择负责人"
              disabled={!!editing && !canManage}
            />
          </Form.Item>
          <Form.Item name="deadline" label="截止时间" rules={[{ required: true, message: '请设置截止时间' }]}>
            <DatePicker showTime style={{ width: '100%' }} />
          </Form.Item>
          <Form.Item name="summary" label="摘要">
            <Input.TextArea rows={3} maxLength={500} placeholder="一句话摘要" />
          </Form.Item>
          <Form.Item name="content" label="底稿（可选）">
            <Input.TextArea rows={6} maxLength={20000} placeholder="可选文字底稿，排版以长图截图为准" />
          </Form.Item>
          <Form.Item name="imageUrls" label="长图截图">
            <LongImageUpload />
          </Form.Item>
        </Form>
      </GlassModal>

      {/* 审核（以长图为审核载体） */}
      <GlassModal
        title="审核推文"
        open={!!reviewTarget}
        onCancel={() => {
          setReviewTarget(null)
          reviewForm.resetFields()
        }}
        footer={
          <Space>
            <Button danger loading={reviewing} onClick={() => handleReview(false)}>
              驳回
            </Button>
            <Button type="primary" loading={reviewing} icon={<CheckOutlined />} onClick={() => handleReview(true)}>
              通过（待发布）
            </Button>
          </Space>
        }
      >
        {reviewTarget && (
          <>
            <Typography.Title level={5} style={{ marginTop: 0 }}>
              {reviewTarget.title}
            </Typography.Title>
            <Typography.Paragraph type="secondary">{reviewTarget.summary}</Typography.Paragraph>
            {reviewTarget.imageUrls?.map((u) => (
              <img
                key={u}
                src={u}
                alt="长图"
                style={{ width: '100%', borderRadius: 8, marginBottom: 8, display: 'block' }}
              />
            ))}
            <Form form={reviewForm} layout="vertical" preserve={false}>
              <Form.Item name="comment" label="审核意见">
                <Input.TextArea rows={3} maxLength={500} placeholder="选填，驳回时建议填写原因" />
              </Form.Item>
            </Form>
          </>
        )}
      </GlassModal>

      {/* 标记发布 */}
      <GlassModal
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
              「{publishTarget.title}」已通过审核，填公众号发布链接后完成发布归档。
            </Typography.Paragraph>
            <Form form={publishForm} layout="vertical" preserve={false}>
              <Form.Item name="wxUrl" label="公众号链接" rules={[{ required: true, message: '请输入公众号链接' }]}>
                <Input maxLength={500} placeholder="https://mp.weixin.qq.com/s/..." />
              </Form.Item>
            </Form>
          </>
        )}
      </GlassModal>

      {/* 更新数据 */}
      <GlassModal
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
            initialValues={{ readCount: statsTarget.readCount ?? 0, likeCount: statsTarget.likeCount ?? 0 }}
          >
            <Form.Item name="readCount" label="阅读量" rules={[{ required: true, message: '请输入阅读量' }]}>
              <InputNumber min={0} precision={0} style={{ width: '100%' }} />
            </Form.Item>
            <Form.Item name="likeCount" label="在看数" rules={[{ required: true, message: '请输入在看数' }]}>
              <InputNumber min={0} precision={0} style={{ width: '100%' }} />
            </Form.Item>
          </Form>
        )}
      </GlassModal>

      {/* 长图大图预览 */}
      <GlassModal
        title="长图预览"
        open={!!previewTarget}
        onCancel={() => setPreviewTarget(null)}
        footer={<Button onClick={() => setPreviewTarget(null)}>关闭</Button>}
      >
        {previewTarget && <img src={previewTarget} alt="长图预览" style={{ width: '100%' }} />}
      </GlassModal>
    </div>
  )
}
