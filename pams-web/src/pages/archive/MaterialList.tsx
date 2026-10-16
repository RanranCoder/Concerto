import FormSurface, { FormSection } from '@/components/form/FormSurface'
import { useCallback, useEffect, useRef, useState } from 'react'
import {
  App,
  Button,
  Descriptions,
  Form,
  Input,
  Popconfirm,
  Select,
  Space,
} from 'antd'
import {
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  EyeOutlined,
  PlusOutlined,
} from '@ant-design/icons'
import { useNavigate, useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassModal from '@/components/glass/GlassModal'
import GlassTable from '@/components/glass/GlassTable'
import PageHeader from '@/components/glass/PageHeader'
import { useHasPerm } from '@/components/glass/RequireRole'
import {
  FileMark,
  RecordsDirectory,
  RecordsName,
  RecordsNav,
} from '@/components/records/RecordsLayout'
import UploadFile from '@/components/glass/UploadFile'
import FilePreviewModal from '@/components/file/FilePreviewModal'
import {
  createMaterial,
  updateMaterial,
  deleteMaterial,
  listMaterials,
  MATERIAL_BIZ_TYPE_MAP,
  MATERIAL_BIZ_TYPE_OPTIONS,
  type MaterialVO,
} from '@/api/material'
import { listActivities, type ActivityVO } from '@/api/activity'
import { listDepts, type DeptVO } from '@/api/dept'
import { downloadFile } from '@/api/file'

interface MaterialFormValues {
  name: string
  bizType: string
  activityId?: number
  deptId?: number
  tag?: string
  description?: string
}
export default function MaterialList() {
  const { message } = App.useApp()
  const hasPerm = useHasPerm()
  const navigate = useNavigate()
  const [params] = useSearchParams()
  const [keyword, setKeyword] = useState('')
  const [query, setQuery] = useState('')
  const [type, setType] = useState<string>()
  const [activityId, setActivityId] = useState<number | undefined>(
    () => Number(params.get('activityId')) || undefined,
  )
  const [rows, setRows] = useState<MaterialVO[]>([])
  const [total, setTotal] = useState(0)
  const [page, setPage] = useState(1)
  const [size, setSize] = useState(10)
  const [activities, setActivities] = useState<ActivityVO[]>([])
  const [depts, setDepts] = useState<DeptVO[]>([])
  const [loading, setLoading] = useState(false)
  const [editing, setEditing] = useState<MaterialVO | null>(null)
  const [open, setOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [fileId, setFileId] = useState<number | null>(null)
  const [detail, setDetail] = useState<MaterialVO | null>(null)
  const [preview, setPreview] = useState<MaterialVO | null>(null)
  const [form] = Form.useForm<MaterialFormValues>()
  const request = useRef(0)
  const fetchList = useCallback(async () => {
    const current = ++request.current
    setLoading(true)
    try {
      const r = await listMaterials({
        keyword: query || undefined,
        bizType: type,
        activityId,
        page,
        size,
      })
      if (current === request.current) {
        setRows(r.records ?? [])
        setTotal(r.total)
      }
    } catch {
      /* HTTP layer reports errors */
    } finally {
      if (current === request.current) setLoading(false)
    }
  }, [query, type, activityId, page, size])
  useEffect(() => {
    fetchList()
  }, [fetchList])
  useEffect(() => {
    listActivities({ size: 1000 })
      .then((r) => setActivities(r.records ?? []))
      .catch(() => {})
    listDepts()
      .then(setDepts)
      .catch(() => {})
  }, [])
  const activityName = (id: number | null) =>
    id == null
      ? '公共资料'
      : (activities.find((a) => a.id === id)?.name ?? `活动 #${id}`)
  const startEdit = (row?: MaterialVO) => {
    setEditing(row ?? null)
    setFileId(row?.fileId ?? null)
    setOpen(true)
  }
  const save = async () => {
    const v = await form.validateFields()
    if (!fileId && !editing) {
      message.warning('请先上传材料文件')
      return
    }
    setSaving(true)
    try {
      const payload = {
        ...v,
        name: v.name.trim(),
        activityId: v.activityId ?? null,
        deptId: v.deptId ?? null,
        tag: v.tag?.trim() || null,
        description: v.description?.trim() || null,
        fileId,
      }
      if (editing) await updateMaterial(editing.id, payload)
      else await createMaterial(payload)
      message.success(editing ? '归档信息已更新' : '材料已归档')
      setOpen(false)
      await fetchList()
    } finally {
      setSaving(false)
    }
  }
  const remove = async (id: number) => {
    await deleteMaterial(id)
    message.success('材料已删除')
    if (rows.length === 1 && page > 1) setPage(page - 1)
    else fetchList()
  }
  return (
    <div className="records-page">
      <PageHeader
        title="材料与档案"
        description="按活动与业务类型归集材料，复用模板，查阅组织工作档案。"
        extra={
          hasPerm('material:upload') && (
            <Button
              type="primary"
              icon={<PlusOutlined />}
              onClick={() => startEdit()}
            >
              上传材料
            </Button>
          )
        }
      />
      <RecordsNav section="archive" />
      <div className="records-layout">
        <RecordsDirectory
          title="材料分类"
          value={type ?? 'ALL'}
          items={[
            { key: 'ALL', label: '全部材料' },
            ...MATERIAL_BIZ_TYPE_OPTIONS.map((o) => ({
              key: o.value,
              label: o.label,
            })),
          ]}
          onChange={(v) => {
            setType(v === 'ALL' ? undefined : v)
            setPage(1)
          }}
        />
        <div className="records-main">
          <GlassCard className="records-toolbar">
            <Space wrap>
              <Input.Search
                aria-label="搜索材料"
                value={keyword}
                onChange={(e) => setKeyword(e.target.value)}
                allowClear
                placeholder="搜索材料名称"
                style={{ width: 260 }}
                onSearch={(v) => {
                  setQuery(v)
                  setPage(1)
                }}
              />
              <Select
                aria-label="按活动筛选材料"
                placeholder="全部活动"
                allowClear
                showSearch
                optionFilterProp="label"
                value={activityId}
                options={activities.map((a) => ({
                  value: a.id,
                  label: a.name,
                }))}
                style={{ width: 220 }}
                onChange={(v) => {
                  setActivityId(v)
                  setPage(1)
                }}
              />
              <Button
                type="link"
                onClick={() => {
                  setKeyword('')
                  setQuery('')
                  setType(undefined)
                  setActivityId(undefined)
                  setPage(1)
                }}
              >
                重置
              </Button>
            </Space>
          </GlassCard>
          <GlassTable<MaterialVO>
            rowKey="id"
            loading={loading}
            dataSource={rows}
            scroll={{ x: 950 }}
            pagination={{
              current: page,
              pageSize: size,
              total,
              showSizeChanger: true,
              showTotal: (t) => `共 ${t} 份材料`,
              onChange: (p, s) => {
                setPage(p)
                setSize(s)
              },
            }}
            locale={{ emptyText: '没有匹配的材料，可调整筛选或上传材料' }}
            columns={[
              {
                title: '文件',
                width: 300,
                render: (_, r) => (
                  <div className="records-name">
                    <FileMark name={r.originFilename || r.name} />
                    <RecordsName
                      name={r.name}
                      meta={`${MATERIAL_BIZ_TYPE_MAP[r.bizType] ?? r.bizType}${r.tag ? ' / ' + r.tag : ''}`}
                    />
                  </div>
                ),
              },
              {
                title: '关联活动',
                width: 210,
                render: (_, r) => activityName(r.activityId),
              },
              {
                title: '所属部门',
                width: 120,
                render: (_, r) =>
                  depts.find((d) => d.id === r.deptId)?.name ??
                  (r.deptId == null ? '公共资料' : `部门 #${r.deptId}`),
              },
              {
                title: '上传日期',
                width: 120,
                render: (_, r) => dayjs(r.createdAt).format('YYYY-MM-DD'),
              },
              {
                title: '操作',
                width: 220,
                render: (_, r) => (
                  <Space size="small" wrap>
                    <Button
                      type="link"
                      size="small"
                      onClick={() => setDetail(r)}
                    >
                      详情
                    </Button>
                    {r.fileId && (
                      <Button
                        type="link"
                        size="small"
                        icon={<EyeOutlined />}
                        onClick={() => setPreview(r)}
                      >
                        预览
                      </Button>
                    )}
                    {r.fileId && (
                      <Button
                        type="link"
                        size="small"
                        icon={<DownloadOutlined />}
                        onClick={() =>
                          downloadFile(r.fileId!, r.originFilename || r.name)
                        }
                      >
                        下载
                      </Button>
                    )}
                    {hasPerm('material:upload') && (
                      <Button
                        type="link"
                        size="small"
                        icon={<EditOutlined />}
                        onClick={() => startEdit(r)}
                      >
                        编辑
                      </Button>
                    )}
                    {hasPerm('material:delete') && (
                      <Popconfirm
                        title="确认删除该材料？"
                        okText="删除"
                        cancelText="取消"
                        onConfirm={() => remove(r.id)}
                      >
                        <Button
                          type="link"
                          danger
                          size="small"
                          icon={<DeleteOutlined />}
                        >
                          删除
                        </Button>
                      </Popconfirm>
                    )}
                  </Space>
                ),
              },
            ]}
          />
        </div>
      </div>
      <FormSurface
        mode="drawer"
        description="填写材料信息，便于按活动、类型和部门查找。"
        title={editing ? '编辑归档信息' : '上传材料'}
        open={open}
        onCancel={() => setOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={save}>
              保存
            </Button>
          </Space>
        }
      >
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          key={editing?.id ?? 'new'}
          initialValues={
            editing
              ? {
                  ...editing,
                  activityId: editing.activityId ?? undefined,
                  deptId: editing.deptId ?? undefined,
                }
              : { bizType: 'OTHER', activityId }
          }
        >
          <FormSection title="材料信息">
            <Form.Item
              label="材料名称"
              name="name"
              rules={[
                { required: true, whitespace: true, message: '请输入材料名称' },
              ]}
            >
              <Input maxLength={150} />
            </Form.Item>
            <Form.Item
              label="业务类型"
              name="bizType"
              rules={[{ required: true }]}
            >
              <Select options={MATERIAL_BIZ_TYPE_OPTIONS} />
            </Form.Item>
            <Form.Item label="标签" name="tag">
              <Input maxLength={200} />
            </Form.Item>
            <Form.Item label="说明" name="description">
              <Input.TextArea rows={3} maxLength={1000} />
            </Form.Item>
          </FormSection>
          <FormSection title="归档位置">
            <Form.Item label="关联活动" name="activityId">
              <Select
                allowClear
                showSearch
                optionFilterProp="label"
                options={activities.map((a) => ({
                  value: a.id,
                  label: a.name,
                }))}
              />
            </Form.Item>
            <Form.Item label="所属部门" name="deptId">
              <Select
                allowClear
                options={depts.map((d) => ({ value: d.id, label: d.name }))}
              />
            </Form.Item>
          </FormSection>
          <FormSection title="材料文件">
            {!editing ? (
              <Form.Item label="文件" required>
                <UploadFile onUploaded={setFileId} />
              </Form.Item>
            ) : (
              <p className="records-note">
                当前文件：{editing.originFilename || editing.name}
              </p>
            )}
          </FormSection>
        </Form>
      </FormSurface>
      <GlassModal
        title="归档信息"
        open={detail != null}
        onCancel={() => setDetail(null)}
        footer={<Button onClick={() => setDetail(null)}>关闭</Button>}
      >
        {detail && (
          <div className="records-panel">
            <Descriptions
              column={1}
              layout="vertical"
              items={[
                { key: 'name', label: '文件', children: detail.name },
                {
                  key: 'activity',
                  label: '关联活动',
                  children: activityName(detail.activityId),
                },
                {
                  key: 'type',
                  label: '业务类型',
                  children:
                    MATERIAL_BIZ_TYPE_MAP[detail.bizType] ?? detail.bizType,
                },
                {
                  key: 'uploader',
                  label: '上传人',
                  children:
                    detail.uploaderId == null
                      ? '未记录'
                      : `#${detail.uploaderId}`,
                },
                { key: 'tag', label: '标签', children: detail.tag || '未填写' },
                {
                  key: 'description',
                  label: '说明',
                  children: detail.description || '未填写',
                },
              ]}
            />
            {detail.activityId != null && (
              <div className="records-related">
                <Button
                  type="link"
                  onClick={() => navigate(`/activities/${detail.activityId}`)}
                >
                  查看关联活动
                </Button>
              </div>
            )}
          </div>
        )}
      </GlassModal>
      <FilePreviewModal
        open={preview != null}
        onClose={() => setPreview(null)}
        fileId={preview?.fileId ?? 0}
        fileName={preview?.originFilename || preview?.name || ''}
      />
    </div>
  )
}
