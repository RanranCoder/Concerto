import { useCallback, useEffect, useState } from 'react'
import { App, Button, Form, Input, Select, Space, Upload } from 'antd'
import { FileTextOutlined, PlusOutlined } from '@ant-design/icons'
import {
  listMaterials,
  createMaterial,
  MATERIAL_BIZ_TYPE_MAP,
  MATERIAL_BIZ_TYPE_OPTIONS,
  type MaterialVO,
} from '@/api/material'
import { uploadFile, downloadFile } from '@/api/file'
import GlassCard from '@/components/glass/GlassCard'
import GlassTable from './ActivityTable'
import FilePreviewModal from '@/components/file/FilePreviewModal'
import FormSurface from '@/components/form/FormSurface'
import { useHasPerm } from '@/components/glass/RequireRole'

export default function ActivityMaterialsTab({
  activityId,
  onChanged,
}: {
  activityId: number
  onChanged: () => void
}) {
  const { message, modal } = App.useApp(),
    hasPerm = useHasPerm(),
    [form] = Form.useForm()
  const [rows, setRows] = useState<MaterialVO[]>([]),
    [total, setTotal] = useState(0),
    [page, setPage] = useState(1)
  const [keyword, setKeyword] = useState(''),
    [bizType, setBizType] = useState<string>(),
    [loading, setLoading] = useState(false),
    [failed, setFailed] = useState(false)
  const [preview, setPreview] = useState<MaterialVO | null>(null),
    [open, setOpen] = useState(false),
    [saving, setSaving] = useState(false),
    [file, setFile] = useState<File | null>(null)
  const load = useCallback(async () => {
    setLoading(true)
    setFailed(false)
    try {
      const result = await listMaterials({
        activityId,
        keyword: keyword || undefined,
        bizType,
        page,
        size: 10,
      })
      setRows(result.records)
      setTotal(result.total)
    } catch {
      setFailed(true)
    } finally {
      setLoading(false)
    }
  }, [activityId, keyword, bizType, page])
  useEffect(() => {
    void load()
  }, [load])
  const close = () => {
    if (saving) return
    const discard = () => {
      setOpen(false)
      setFile(null)
      form.resetFields()
    }
    if (file || form.isFieldsTouched())
      modal.confirm({
        title: '放弃未上传的材料？',
        content: '所选文件和材料说明尚未保存。',
        okText: '放弃修改',
        cancelText: '继续编辑',
        onOk: discard,
      })
    else discard()
  }
  const save = async () => {
    let values
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    if (!file) {
      message.warning('请选择文件')
      return
    }
    setSaving(true)
    try {
      const uploaded = await uploadFile(file, values.bizType)
      await createMaterial({
        activityId,
        fileId: uploaded.id,
        bizType: values.bizType,
        name: file.name,
        description: values.description,
      })
      setOpen(false)
      setFile(null)
      form.resetFields()
      await load()
      onChanged()
      message.success('材料已上传并关联当前活动')
    } catch {
      /* Keep the selected file and metadata so the upload can be retried. */
    } finally {
      setSaving(false)
    }
  }
  return (
    <GlassCard className="activity-materials-pane">
      <header className="activity-panel-head">
        <div>
          <h2>活动材料</h2>
          <p>集中查看当前活动关联的文件</p>
        </div>
        {hasPerm('material:upload') && (
          <Button
            type="primary"
            icon={<PlusOutlined />}
            onClick={() => setOpen(true)}
          >
            上传材料
          </Button>
        )}
      </header>
      <div className="activity-pane-toolbar">
        <Input.Search
          allowClear
          aria-label="搜索材料名称"
          placeholder="搜索材料名称"
          onSearch={(value) => {
            setKeyword(value)
            setPage(1)
          }}
        />
        <Select
          allowClear
          aria-label="材料类型"
          placeholder="全部材料"
          options={MATERIAL_BIZ_TYPE_OPTIONS}
          value={bizType}
          onChange={(value) => {
            setBizType(value)
            setPage(1)
          }}
        />
        <span>共 {total} 份</span>
      </div>
      {failed && (
        <p role="alert" className="activity-inline-note error">
          材料加载失败。
          <Button type="link" onClick={load}>
            重新加载
          </Button>
        </p>
      )}
      <GlassTable<MaterialVO>
        rowKey="id"
        loading={loading}
        dataSource={rows}
        scroll={{ x: 700 }}
        columns={[
          {
            title: '文件名称',
            dataIndex: 'name',
            render: (name: string, record) => (
              <Space>
                <FileTextOutlined />
                <div>
                  <Button
                    type="link"
                    disabled={!record.fileId}
                    onClick={() => setPreview(record)}
                  >
                    {name}
                  </Button>
                  <small className="business-table-meta">
                    {record.originFilename ||
                      MATERIAL_BIZ_TYPE_MAP[record.bizType]}
                  </small>
                </div>
              </Space>
            ),
          },
          {
            title: '材料类型',
            dataIndex: 'bizType',
            render: (value: string) => MATERIAL_BIZ_TYPE_MAP[value] || value,
          },
          {
            title: '上传时间',
            dataIndex: 'createdAt',
            render: (value: string) =>
              value?.replace('T', ' ').slice(0, 16) || '—',
          },
          {
            title: '操作',
            render: (_, r) => (
              <Space>
                <Button
                  type="link"
                  disabled={!r.fileId}
                  onClick={() => setPreview(r)}
                >
                  预览
                </Button>
                <Button
                  type="link"
                  disabled={!r.fileId}
                  onClick={() =>
                    r.fileId &&
                    downloadFile(r.fileId, r.originFilename || r.name)
                  }
                >
                  下载
                </Button>
              </Space>
            ),
          },
        ]}
        pagination={{ current: page, pageSize: 10, total, onChange: setPage }}
      />
      {preview?.fileId && (
        <FilePreviewModal
          open
          onClose={() => setPreview(null)}
          fileId={preview.fileId}
          fileName={preview.originFilename || preview.name}
        />
      )}
      <FormSurface
        mode="drawer"
        title="上传活动材料"
        description="上传后自动关联当前活动。"
        open={open}
        onCancel={close}
        footer={
          <Space>
            <Button onClick={close} disabled={saving}>
              取消
            </Button>
            <Button type="primary" loading={saving} onClick={save}>
              上传并关联
            </Button>
          </Space>
        }
      >
        <Form
          form={form}
          layout="vertical"
          initialValues={{ bizType: 'OTHER' }}
        >
          <Form.Item label="文件" required>
            <Upload
              beforeUpload={() => false}
              onChange={(info) =>
                setFile(info.fileList.at(-1)?.originFileObj ?? null)
              }
              fileList={
                file
                  ? [{ uid: 'selected', name: file.name, status: 'done' }]
                  : []
              }
              onRemove={() => {
                setFile(null)
                return true
              }}
              maxCount={1}
            >
              <Button>选择文件</Button>
            </Upload>
          </Form.Item>
          <Form.Item
            name="bizType"
            label="材料类型"
            rules={[{ required: true }]}
          >
            <Select options={MATERIAL_BIZ_TYPE_OPTIONS} />
          </Form.Item>
          <Form.Item name="description" label="材料说明">
            <Input.TextArea rows={4} maxLength={1000} />
          </Form.Item>
        </Form>
      </FormSurface>
    </GlassCard>
  )
}
