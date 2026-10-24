import FormSurface from '@/components/form/FormSurface'
import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import {
  App,
  Button,
  DatePicker,
  Empty,
  Form,
  Input,
  Popconfirm,
  Select,
  Space,
  Tag,
} from 'antd'
import {
  DeleteOutlined,
  DownloadOutlined,
  PlusOutlined,
} from '@ant-design/icons'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import GlassTable from './ActivityTable'
import SigninQR from '@/components/signin/SigninQR'
import SignInGroupList from '@/components/signin/SignInGroupList'
import SigninFieldConfig from '@/components/signin/SigninFieldConfig'
import { signInGroupSummary } from '@/api/signinGroup'
import { useAuthStore } from '@/stores/auth'
import {
  countSignins,
  createSignin,
  deleteSignin,
  listSignins,
  type SigninSave,
  type SigninSummaryVO,
  type SigninVO,
} from '@/api/signin'

const SIGN_TYPE_TEXT: Record<string, string> = { MANUAL: '手动', SCAN: '扫码' }
const IDENTITY_OPTIONS = ['党建干事', '发展对象', '预备党员', '入党积极分子']

/** CSV 字段转义：含逗号/引号/换行的字段加引号包裹并转义内部引号 */
function csvCell(v: string | null | undefined): string {
  const s = v ?? ''
  if (/[",\n\r]/.test(s)) return `"${s.replace(/"/g, '""')}"`
  return s
}

/** 把签到列表转为 CSV（带中文表头与 BOM），并通过 Blob + a.download 下载 */
function exportCsv(rows: SigninVO[]) {
  const header = ['序号', '姓名', '学号', '班级', '身份', '签到时间']
  const lines = rows.map((r, i) =>
    [
      String(i + 1),
      csvCell(r.name),
      csvCell(r.studentNo),
      csvCell(r.className),
      csvCell(r.identityType),
      r.signTime ? dayjs(r.signTime).format('YYYY-MM-DD HH:mm') : '',
    ].join(','),
  )
  const csv = '﻿' + [header.join(','), ...lines].join('\r\n')
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = `签到名单_${dayjs().format('YYYYMMDD_HHmmss')}.csv`
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}

export default function SigninPanel({
  activityId,
  active = true,
}: {
  activityId: number
  active?: boolean
}) {
  const { message } = App.useApp()
  const [list, setList] = useState<SigninVO[]>([])
  const [count, setCount] = useState(0)
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [saving, setSaving] = useState(false)
  const [formInit, setFormInit] = useState<Record<string, unknown>>()
  const [form] = Form.useForm()

  // 应签名单：汇总 / 刷新信号（上传、字段配置、补签、扫码、手动签到均 bump）
  const isMinisterOrAbove = useAuthStore(
    (s) => s.user?.permissions?.includes('checkin:manage') ?? false,
  )
  const [rosterSummaryData, setRosterSummaryData] =
    useState<SigninSummaryVO | null>(null)
  const [view, setView] = useState<'roster' | 'records'>(
    isMinisterOrAbove ? 'roster' : 'records',
  )
  const [qrOpen, setQrOpen] = useState(false)
  const [query, setQuery] = useState('')
  const [rosterVersion, setRosterVersion] = useState(0)
  const bumpRoster = () => setRosterVersion((v) => v + 1)

  const fetchData = useCallback(async () => {
    setLoading(true)
    try {
      const [rows, c] = await Promise.all([
        listSignins(activityId),
        countSignins(activityId),
      ])
      setList(rows ?? [])
      setCount(c ?? 0)
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [activityId])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  const fetchRosterSummary = useCallback(async () => {
    if (!isMinisterOrAbove) return
    try {
      setRosterSummaryData(
        await signInGroupSummary(activityId).then((result) => ({
          expected: result.total,
          signed: result.signed,
          unsigned: result.unsigned,
        })),
      )
    } catch {
      /* 干事无名单权限，静默 */
    }
  }, [activityId, isMinisterOrAbove])

  useEffect(() => {
    fetchRosterSummary()
    // rosterVersion 变化时重拉汇总（上传/补签/扫码后人数变化）
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [fetchRosterSummary, rosterVersion])

  /** 全量刷新：签到记录 + 应签汇总 + 名单列表（扫码/手动签到/补签后调用） */
  const refreshAll = useCallback(() => {
    fetchData()
    bumpRoster()
  }, [fetchData])

  const openCreate = () => {
    // GlassModal destroyOnHidden 关闭即卸载，回填用 initialValues（挂载时生效）
    setFormInit({ signType: 'MANUAL', signTime: dayjs() })
    setModalOpen(true)
  }

  const handleSave = async () => {
    let values
    try {
      values = await form.validateFields()
    } catch {
      return
    }
    setSaving(true)
    try {
      const payload: SigninSave = {
        activityId,
        name: values.name,
        studentNo: values.studentNo || null,
        className: values.className || null,
        identityType: values.identityType || null,
        signType: values.signType ?? 'MANUAL',
        signTime: values.signTime
          ? values.signTime.format('YYYY-MM-DD HH:mm:ss')
          : null,
        location: values.location || null,
      }
      await createSignin(payload)
      message.success('已新增签到')
      setModalOpen(false)
      refreshAll()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setSaving(false)
    }
  }

  const handleDelete = async (id: number) => {
    try {
      await deleteSignin(id)
      message.success('已删除')
      refreshAll()
    } catch {
      /* http 拦截已提示 */
    }
  }

  const columns = useMemo<ColumnsType<SigninVO>>(
    () => [
      { title: '姓名', dataIndex: 'name', key: 'name' },
      {
        title: '学号',
        dataIndex: 'studentNo',
        key: 'studentNo',
        render: (v: string | null) => v || '-',
      },
      {
        title: '班级',
        dataIndex: 'className',
        key: 'className',
        render: (v: string | null) => v || '-',
      },
      {
        title: '身份',
        dataIndex: 'identityType',
        key: 'identityType',
        render: (v: string | null) => v || '-',
      },
      {
        title: '签到方式',
        dataIndex: 'signType',
        key: 'signType',
        width: 100,
        render: (v: string | null) => (v ? (SIGN_TYPE_TEXT[v] ?? v) : '-'),
      },
      {
        title: '签到时间',
        dataIndex: 'signTime',
        key: 'signTime',
        width: 160,
        render: (v: string | null) =>
          v ? dayjs(v).format('YYYY-MM-DD HH:mm') : '-',
      },
      {
        title: '定位',
        dataIndex: 'location',
        key: 'location',
        render: (v: string | null) => v || '-',
      },
      {
        title: '操作',
        key: 'action',
        align: 'right',
        width: 80,
        render: (_: unknown, r: SigninVO) =>
          isMinisterOrAbove ? (
            <Popconfirm
              title="确认删除该签到记录？"
              onConfirm={() => handleDelete(r.id)}
              okText="删除"
              cancelText="取消"
            >
              <Button
                type="link"
                size="small"
                danger
                icon={<DeleteOutlined />}
              />
            </Popconfirm>
          ) : null,
      },
    ],
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [isMinisterOrAbove],
  )

  const summary = rosterSummaryData
  const filteredRows = list.filter((row) =>
    [row.name, row.studentNo, row.className].join(' ').includes(query),
  )

  return (
    <div className="activity-signin-pane">
      <div className="activity-signin-metrics">
        <div>
          {(isMinisterOrAbove
            ? [
                ['应签人数', summary?.expected ?? '—'],
                ['已签到', summary?.signed ?? '—'],
                ['未签到', summary?.unsigned ?? '—'],
                [
                  '签到率',
                  summary?.expected
                    ? Math.round((summary.signed / summary.expected) * 100) +
                      '%'
                    : '—',
                ],
              ]
            : [['签到记录', count]]
          ).map(([label, value]) => (
            <div key={String(label)}>
              <small>{label}</small>
              <strong>{value}</strong>
            </div>
          ))}
        </div>
        <Button type="primary" onClick={() => setQrOpen(true)}>
          展示签到码
        </Button>
      </div>
      <nav className="activity-signin-tabs" aria-label="签到管理视图">
        {isMinisterOrAbove && (
          <button
            aria-pressed={view === 'roster'}
            onClick={() => setView('roster')}
          >
            应签名单
          </button>
        )}
        <button
          aria-pressed={view === 'records'}
          onClick={() => setView('records')}
        >
          签到记录
        </button>
      </nav>
      {/* 应签名单区（仅部长及以上：后端名单/字段/补签接口 @PreAuthorize 部长及以上） */}
      {isMinisterOrAbove && view === 'roster' && (
        <GlassCard style={{ padding: 16, marginBottom: 16 }}>
          <Space
            wrap
            style={{ display: 'flex', justifyContent: 'space-between' }}
          >
            <Space wrap>
              <span
                style={{
                  fontSize: 14,
                  fontWeight: 'calc(600 - var(--font-weight-compensation))',
                  color: 'var(--brand-text)',
                }}
              >
                应签名单
              </span>
              <Tag color="red">应签 {summary?.expected ?? 0}</Tag>
              <Tag color="green">已签 {summary?.signed ?? 0}</Tag>
              <Tag color="orange">未签 {summary?.unsigned ?? 0}</Tag>
            </Space>
          </Space>

          <div style={{ marginTop: 12 }}>
            <SignInGroupList
              activityId={activityId}
              reloadKey={rosterVersion}
              onChanged={refreshAll}
            />
          </div>
        </GlassCard>
      )}

      <FormSurface
        mode="drawer"
        title="扫码签到"
        open={qrOpen}
        onCancel={() => setQrOpen(false)}
        footer={<Button onClick={() => setQrOpen(false)}>关闭</Button>}
      >
        <Space
          wrap
          style={{
            display: 'flex',
            justifyContent: 'space-between',
            marginBottom: 12,
          }}
        >
          <span style={{ fontSize: 14, fontWeight: 'calc(600 - var(--font-weight-compensation))' }}>扫码签到</span>
          <SigninFieldConfig activityId={activityId} onChanged={refreshAll} />
        </Space>
        <SigninQR
          activityId={activityId}
          active={active && qrOpen}
          onSigned={refreshAll}
        />
      </FormSurface>
      {view === 'records' && (
        <>
          <GlassCard style={{ padding: 16, marginBottom: 16 }}>
            <Space
              wrap
              style={{ display: 'flex', justifyContent: 'space-between' }}
            >
              <Space wrap>
                {/* 不再内联放字号/内边距：同页其它 Tag 都走 global.css 的 caption 档，这个标签会高出一截 */}
                <Tag color="red">总签到 {count} 人</Tag>
                <Input.Search
                  aria-label="搜索签到记录"
                  placeholder="搜索姓名 / 学号 / 班级"
                  allowClear
                  onChange={(e) => setQuery(e.target.value)}
                />
              </Space>
              <Space wrap>
                <Button
                  icon={<DownloadOutlined />}
                  onClick={() => exportCsv(list)}
                  disabled={list.length === 0}
                >
                  导出 CSV
                </Button>
                {isMinisterOrAbove && (
                  <Button
                    type="primary"
                    icon={<PlusOutlined />}
                    onClick={openCreate}
                  >
                    新增签到
                  </Button>
                )}
              </Space>
            </Space>
          </GlassCard>

          <GlassTable<SigninVO>
            columns={columns}
            dataSource={filteredRows.map((x) => ({ ...x, key: x.id }))}
            rowKey="id"
            loading={loading}
            pagination={
              list.length > 10
                ? { pageSize: 10, showTotal: (t) => `共 ${t} 条` }
                : false
            }
            locale={{ emptyText: <Empty description="暂无签到记录" /> }}
          />
        </>
      )}
      <FormSurface
        mode="drawer"
        description="补录人员、签到方式和时间，保存后加入签到记录。"
        title="新增签到"
        open={modalOpen}
        onCancel={() => setModalOpen(false)}
        footer={
          <Space>
            <Button onClick={() => setModalOpen(false)}>取消</Button>
            <Button type="primary" loading={saving} onClick={handleSave}>
              保存签到记录
            </Button>
          </Space>
        }
      >
        <Form
          form={form}
          layout="vertical"
          preserve={false}
          initialValues={formInit}
        >
          <Form.Item
            name="name"
            label="姓名"
            rules={[{ required: true, message: '请输入姓名' }]}
          >
            <Input maxLength={50} placeholder="请输入姓名" />
          </Form.Item>
          <Form.Item name="studentNo" label="学号">
            <Input maxLength={20} placeholder="请输入学号" />
          </Form.Item>
          <Form.Item name="className" label="班级">
            <Input maxLength={100} placeholder="请输入班级" />
          </Form.Item>
          <Form.Item name="identityType" label="身份">
            <Select
              placeholder="选择身份"
              allowClear
              options={IDENTITY_OPTIONS.map((v) => ({ value: v, label: v }))}
            />
          </Form.Item>
          <Space size={12} style={{ display: 'flex' }}>
            <Form.Item
              name="signType"
              label="签到方式"
              style={{ flex: 1 }}
              rules={[{ required: true, message: '请选择签到方式' }]}
            >
              <Select
                options={[
                  { value: 'MANUAL', label: '手动' },
                  { value: 'SCAN', label: '扫码' },
                ]}
              />
            </Form.Item>
            <Form.Item name="signTime" label="签到时间" style={{ flex: 1 }}>
              <DatePicker
                showTime
                style={{ width: '100%' }}
                placeholder="选择时间"
              />
            </Form.Item>
          </Space>
          <Form.Item name="location" label="定位">
            <Input maxLength={255} placeholder="如 学院楼 A301" />
          </Form.Item>
        </Form>
      </FormSurface>
    </div>
  )
}
