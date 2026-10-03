import { useCallback, useEffect, useState } from 'react'
import { App, Button, Form, Input, Popconfirm, Space, Tag, Typography } from 'antd'
import type { TableColumnsType } from 'antd'
import { BellOutlined, DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import {
  createAnnouncement,
  deleteAnnouncement,
  listAnnouncements,
  listAnnouncementReadIds,
  markAnnouncementRead,
  type AnnouncementVO,
} from '@/api/announcement'
import { listUsers, type UserVO } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import { useHasPerm } from '@/components/glass/RequireRole'

interface AnnouncementFormValues {
  title: string
  content: string
}

export default function AnnouncementList() {
  const { message } = App.useApp()
  const user = useAuthStore((s) => s.user)
  const hasPerm = useHasPerm()
  // 发布/修改：notice:publish（V7 种子：部长级）；删除：notice:manage（仅 TEACHER/DIRECTOR）

  const [data, setData] = useState<AnnouncementVO[]>([])
  const [loading, setLoading] = useState(false)
  const [users, setUsers] = useState<UserVO[]>([])
  const [readIds, setReadIds] = useState<number[]>([])
  const [publishOpen, setPublishOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [form] = Form.useForm<AnnouncementFormValues>()
  const [detail, setDetail] = useState<AnnouncementVO | null>(null)

  // 已读状态已落库（PRD-v5 F4），随账号走、跨设备一致；未登录兜底为空集合
  const loadReadIds = useCallback(() => {
    if (!user) {
      setReadIds([])
      return
    }
    listAnnouncementReadIds()
      .then((ids) => setReadIds(ids ?? []))
      .catch(() => setReadIds([]))
  }, [user])

  useEffect(() => {
    loadReadIds()
  }, [loadReadIds])

  const fetchList = useCallback(() => {
    setLoading(true)
    listAnnouncements()
      .then((res) => setData(res ?? []))
      .catch(() => {
        /* http 拦截已提示 */
      })
      .finally(() => setLoading(false))
  }, [])

  useEffect(() => {
    fetchList()
  }, [fetchList])

  // 发布人姓名映射（publisherId → realName）
  useEffect(() => {
    listUsers({ size: 1000 })
      .then((res) => setUsers(res.records ?? []))
      .catch(() => {
        /* 干事可能无 /users 权限，显示 #id 兜底 */
      })
  }, [])

  const publisherNameOf = (id: number | null): string => {
    if (id == null) return '-'
    return users.find((u) => u.id === id)?.realName ?? `#${id}`
  }

  const openPublish = () => {
    form.resetFields()
    setPublishOpen(true)
  }

  const handlePublish = async () => {
    const values = await form.validateFields()
    setSaving(true)
    try {
      await createAnnouncement({
        title: values.title.trim(),
        content: values.content.trim(),
      })
      message.success('公告已发布')
      setPublishOpen(false)
      fetchList()
    } catch {
      /* 校验失败或 http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleRead = (a: AnnouncementVO) => {
    setDetail(a)
    if (!readIds.includes(a.id)) {
      setReadIds([...readIds, a.id])
      markAnnouncementRead(a.id).catch(() => {
        /* 后端标记失败不影响阅读，下次进入会重新拉取已读集合 */
      })
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteAnnouncement(id)
      message.success('已删除')
      fetchList()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const columns: TableColumnsType<AnnouncementVO> = [
    {
      title: '标题',
      dataIndex: 'title',
      key: 'title',
      ellipsis: true,
      render: (t: string, r: AnnouncementVO) => (
        <Button type="link" style={{ padding: 0, height: 'auto', textAlign: 'left' }} onClick={() => handleRead(r)}>
          {readIds.includes(r.id) ? t : <b>{t}</b>}
        </Button>
      ),
    },
    {
      title: '发布人',
      key: 'publisher',
      width: 120,
      render: (_: unknown, r: AnnouncementVO) => publisherNameOf(r.publisherId),
    },
    {
      title: '发布时间',
      dataIndex: 'publishTime',
      key: 'publishTime',
      width: 170,
      render: (t: string | null) => (t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-'),
    },
    {
      title: '状态',
      key: 'status',
      width: 100,
      render: (_: unknown, r: AnnouncementVO) =>
        readIds.includes(r.id) ? <Tag>已读</Tag> : <Tag color="processing">未读</Tag>,
    },
    ...(hasPerm('notice:manage')
      ? [
          {
            title: '操作',
            key: 'action',
            width: 100,
            render: (_: unknown, r: AnnouncementVO) => (
              <Popconfirm title="确认删除该公告？" onConfirm={() => handleDelete(r.id)} okText="删除" cancelText="取消">
                <Button type="link" size="small" danger icon={<DeleteOutlined />}>
                  删除
                </Button>
              </Popconfirm>
            ),
          },
        ]
      : []),
  ]

  return (
    <div>
      <PageHeader
        title="通知公告"
        description="党建办公室通知发布（部长以上可发布，删除限老师/主任），已读状态跟随账号"
        extra={
          hasPerm('notice:publish') ? (
            <Button type="primary" icon={<PlusOutlined />} onClick={openPublish}>
              发布公告
            </Button>
          ) : null
        }
      />

      {!hasPerm('notice:publish') && (
        <GlassCard style={{ padding: 12, marginBottom: 16 }}>
          <Typography.Text type="secondary" style={{ fontSize: 13 }}>
            您当前仅可查看公告，发布需部长及以上权限。
          </Typography.Text>
        </GlassCard>
      )}

      <GlassTable<AnnouncementVO>
        columns={columns}
        dataSource={data.map((r) => ({ ...r, key: r.id }))}
        rowKey="id"
        loading={loading}
        pagination={false}
        locale={{ emptyText: '暂无公告' }}
      />

      <GlassModal
        title="发布公告"
        open={publishOpen}
        onCancel={() => setPublishOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setPublishOpen(false)}>取消</Button>
            <Button type="primary" icon={<BellOutlined />} loading={saving} onClick={handlePublish}>
              发布
            </Button>
          </Space>
        }
      >
        <Form form={form} layout="vertical" preserve={false}>
          <Form.Item name="title" label="标题" rules={[{ required: true, message: '请输入公告标题' }]}>
            <Input maxLength={150} placeholder="公告标题" />
          </Form.Item>
          <Form.Item name="content" label="内容" rules={[{ required: true, message: '请输入公告内容' }]}>
            <Input.TextArea rows={8} maxLength={5000} placeholder="公告正文（支持换行）" />
          </Form.Item>
        </Form>
      </GlassModal>

      {/* 查看详情 */}
      <GlassModal
        title={detail?.title ?? '公告详情'}
        open={!!detail}
        onCancel={() => setDetail(null)}
        footer={<Button onClick={() => setDetail(null)}>关闭</Button>}
      >
        {detail && (
          <>
            <div style={{ marginBottom: 12, color: 'var(--text-secondary)', fontSize: 13 }}>
              {publisherNameOf(detail.publisherId)}
              {detail.publishTime ? ` · ${dayjs(detail.publishTime).format('YYYY-MM-DD HH:mm')}` : ''}
              {readIds.includes(detail.id) ? ' · 已读' : ' · 未读'}
            </div>
            <Typography.Paragraph style={{ whiteSpace: 'pre-wrap', marginBottom: 0 }}>
              {detail.content}
            </Typography.Paragraph>
          </>
        )}
      </GlassModal>
    </div>
  )
}
