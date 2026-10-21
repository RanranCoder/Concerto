import FormSurface from '@/components/form/FormSurface'
import { useCallback, useEffect, useMemo, useState } from 'react'
import type { ColumnsType } from 'antd/es/table'
import {
  App,
  Button,
  Empty,
  Form,
  Input,
  InputNumber,
  Popconfirm,
  Space,
  Tooltip,
} from 'antd'
import { DeleteOutlined, PlusOutlined } from '@ant-design/icons'
import { useHasPerm } from '@/components/glass/RequireRole'
import GlassCard from '@/components/glass/GlassCard'
import GlassTable from './ActivityTable'
import {
  createScoreRecord,
  createScoreRule,
  deleteScoreRecord,
  deleteScoreRule,
  getScores,
  type ScoreRecordSave,
  type ScoreRuleSave,
} from '@/api/score'
import type { ScoreRecordVO, ScoreRuleVO } from '@/api/activity'

interface RecordRow {
  key: number
  id: number
  teamName: string
  groupName: string
  total: number | null
  rankNo: number | null
  dims: Record<string, number>
}

/** 标准竞赛名次（1224 制）：按总分降序，同分同名次 */
function computeRanks(rows: RecordRow[]): Map<number, number> {
  const sorted = [...rows].sort((a, b) => (b.total ?? -1) - (a.total ?? -1))
  const map = new Map<number, number>()
  let prev: number | null = null
  sorted.forEach((r, i) => {
    if (r.total !== prev) {
      prev = r.total ?? null
      map.set(r.id, i + 1)
    } else {
      map.set(r.id, map.get(sorted[i - 1].id) ?? i + 1)
    }
  })
  return map
}

function parseDims(json: string | null): Record<string, number> {
  if (!json) return {}
  try {
    const obj = JSON.parse(json) as Record<string, number>
    return typeof obj === 'object' && obj !== null ? obj : {}
  } catch {
    return {}
  }
}

export default function ScorePanel({ activityId }: { activityId: number }) {
  const { message } = App.useApp()
  const hasPerm = useHasPerm()
  const canEdit = hasPerm('activity:edit')
  const [rules, setRules] = useState<ScoreRuleVO[]>([])
  const [records, setRecords] = useState<ScoreRecordVO[]>([])
  const [loading, setLoading] = useState(false)
  const [ruleModal, setRuleModal] = useState(false)
  const [recordModal, setRecordModal] = useState(false)
  const [ruleSaving, setRuleSaving] = useState(false)
  const [recordSaving, setRecordSaving] = useState(false)
  const [ruleForm] = Form.useForm()
  const [recordForm] = Form.useForm()
  const [ruleInit, setRuleInit] = useState<Record<string, unknown>>()

  const fetchData = useCallback(async () => {
    setLoading(true)
    try {
      const { rules: r, records: rec } = await getScores(activityId)
      setRules(r ?? [])
      setRecords(rec ?? [])
    } catch {
      /* http 拦截已提示 */
    } finally {
      setLoading(false)
    }
  }, [activityId])

  useEffect(() => {
    fetchData()
  }, [fetchData])

  // ---- 规则 ----

  const openRule = () => {
    // GlassModal destroyOnHidden 关闭即卸载，回填用 initialValues（挂载时生效）
    setRuleInit({ sortOrder: rules.length + 1 })
    setRuleModal(true)
  }

  const handleSaveRule = async () => {
    let values
    try {
      values = await ruleForm.validateFields()
    } catch {
      return
    }
    setRuleSaving(true)
    try {
      const payload: ScoreRuleSave = {
        activityId,
        dimensionName: values.dimensionName,
        fullMarks: values.fullMarks,
        sortOrder: values.sortOrder ?? 0,
      }
      await createScoreRule(payload)
      message.success('评分规则已新增')
      setRuleModal(false)
      fetchData()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setRuleSaving(false)
    }
  }

  const handleDeleteRule = async (id: number) => {
    try {
      await deleteScoreRule(id)
      message.success('规则已删除')
      fetchData()
    } catch {
      /* http 拦截已提示 */
    }
  }

  // ---- 记录 ----

  const openRecord = () => {
    recordForm.resetFields()
    setRecordModal(true)
  }

  const handleSaveRecord = async () => {
    let values
    try {
      values = await recordForm.validateFields()
    } catch {
      return
    }
    setRecordSaving(true)
    try {
      // 维度边界校验兜底：维度必须是有效规则 id，且分值落在 0~满分 之间
      const dims: Record<string, number> = {}
      const errs: string[] = []
      for (const rule of rules) {
        const v = values[`dim_${rule.id}`]
        if (v === undefined || v === null || v === '') continue
        const n = Number(v)
        if (!Number.isFinite(n) || n < 0 || n > rule.fullMarks) {
          errs.push(
            `「${rule.dimensionName}」分值需在 0~${rule.fullMarks} 之间`,
          )
        } else {
          dims[String(rule.id)] = n
        }
      }
      if (errs.length) {
        message.error(errs.join('；'))
        return
      }
      if (Object.keys(dims).length === 0) {
        message.error('请至少填写一个评分维度')
        return
      }
      const payload: ScoreRecordSave = {
        activityId,
        teamName: values.teamName,
        groupName: values.groupName || null,
        dimensionScores: JSON.stringify(dims),
        rankNo: values.rankNo ?? null,
        remark: values.remark || null,
      }
      await createScoreRecord(payload)
      message.success('评分记录已录入')
      setRecordModal(false)
      fetchData()
    } catch {
      /* http 拦截已提示 */
    } finally {
      setRecordSaving(false)
    }
  }

  const handleDeleteRecord = async (id: number) => {
    try {
      await deleteScoreRecord(id)
      message.success('记录已删除')
      fetchData()
    } catch {
      /* http 拦截已提示 */
    }
  }

  // ---- 渲染 ----

  const ruleColumns = useMemo<ColumnsType<ScoreRuleVO>>(
    () => [
      { title: '评分维度', dataIndex: 'dimensionName', key: 'dimensionName' },
      {
        title: '分值',
        dataIndex: 'fullMarks',
        key: 'fullMarks',
        width: 100,
        align: 'right',
        onCell: () => ({ style: { fontVariantNumeric: 'tabular-nums' } }),
        render: (v: number) => `${v} 分`,
      },
      {
        title: '排序',
        dataIndex: 'sortOrder',
        key: 'sortOrder',
        width: 80,
        render: (v: number | null) => v ?? 0,
      },
      {
        title: '操作',
        key: 'action',
        align: 'right',
        width: 80,
        render: (_: unknown, r: ScoreRuleVO) =>
          canEdit ? (
            <Popconfirm
              title="确认删除该规则？"
              onConfirm={() => handleDeleteRule(r.id)}
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
    [canEdit],
  )

  const rows = useMemo<RecordRow[]>(
    () =>
      records.map((r) => ({
        key: r.id,
        id: r.id,
        teamName: r.teamName,
        groupName: r.groupName ?? '',
        total: r.total,
        rankNo: r.rankNo,
        dims: parseDims(r.dimensionScores),
      })),
    [records],
  )
  const ranks = useMemo(() => computeRanks(rows), [rows])

  const recordColumns = useMemo<ColumnsType<RecordRow>>(
    () => {
      const base: Array<Record<string, unknown>> = [
        { title: '队名', dataIndex: 'teamName', key: 'teamName' },
        {
          title: '组别',
          dataIndex: 'groupName',
          key: 'groupName',
          render: (v: string) => v || '-',
        },
      ]
      rules.forEach((rule) => {
        base.push({
          title: `${rule.dimensionName} (${rule.fullMarks})`,
          key: `dim_${rule.id}`,
          width: 90,
          render: (_: unknown, row: RecordRow) =>
            row.dims[String(rule.id)] ?? '-',
        })
      })
      base.push({
        title: '总分',
        dataIndex: 'total',
        key: 'total',
        width: 80,
        align: 'right',
        onCell: () => ({ style: { fontVariantNumeric: 'tabular-nums' } }),
        render: (v: number | null) => v ?? 0,
      })
      base.push({
        title: '名次',
        key: 'rank',
        width: 80,
        align: 'right',
        onCell: () => ({ style: { fontVariantNumeric: 'tabular-nums' } }),
        render: (_: unknown, row: RecordRow) => ranks.get(row.id) ?? '-',
      })
      base.push({
        title: '操作',
        key: 'action',
        align: 'right',
        width: 80,
        render: (_: unknown, row: RecordRow) =>
          canEdit ? (
            <Popconfirm
              title="确认删除该评分记录？"
              onConfirm={() => handleDeleteRecord(row.id)}
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
      })
      return base as ColumnsType<RecordRow>
    },
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [rules, rows, ranks, canEdit],
  )

  return (
    <div className="activity-score-pane">
      {rules.length > 0 && (
        <div className="activity-score-rules">
          {rules.map((rule) => (
            <div key={rule.id}>
              <h3>{rule.dimensionName}</h3>
              <div>
                <strong>{rule.fullMarks}</strong>
                <small>分</small>
              </div>
            </div>
          ))}
        </div>
      )}
      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space
          wrap
          style={{ display: 'flex', justifyContent: 'space-between' }}
        >
          <Space wrap>
            <h2 style={{ fontSize: 16, margin: 0 }}>评分规则</h2>
            <span style={{ color: 'var(--text-secondary)', fontSize: 14 }}>
              {rules.length ? `${rules.length} 个维度` : '暂无评分维度'}
            </span>
          </Space>
          {canEdit && (
            <Button type="primary" icon={<PlusOutlined />} onClick={openRule}>
              新增规则
            </Button>
          )}
        </Space>
      </GlassCard>
      <GlassTable<ScoreRuleVO>
        columns={ruleColumns}
        dataSource={rules.map((x) => ({ ...x, key: x.id }))}
        rowKey="id"
        pagination={false}
        locale={{ emptyText: <Empty description="暂无评分规则" /> }}
      />

      <GlassCard style={{ padding: 16, marginTop: 16, marginBottom: 16 }}>
        <Space
          wrap
          style={{ display: 'flex', justifyContent: 'space-between' }}
        >
          <Space wrap>
            <h2 style={{ fontSize: 16, margin: 0 }}>评分结果</h2>
            <span style={{ color: 'var(--text-secondary)', fontSize: 14 }}>
              {records.length} 支队伍
            </span>
          </Space>
          {canEdit &&
            (rules.length ? (
              <Button
                type="primary"
                icon={<PlusOutlined />}
                onClick={openRecord}
              >
                录入记录
              </Button>
            ) : (
              <Tooltip title="请先新增评分规则">
                <Button type="primary" icon={<PlusOutlined />} disabled>
                  录入记录
                </Button>
              </Tooltip>
            ))}
        </Space>
      </GlassCard>
      <GlassTable<RecordRow>
        columns={recordColumns}
        dataSource={rows}
        rowKey="id"
        loading={loading}
        pagination={
          records.length > 10
            ? { pageSize: 10, showTotal: (t) => `共 ${t} 条` }
            : false
        }
        locale={{ emptyText: <Empty description="暂无评分记录" /> }}
      />

      {/* 新增规则弹窗 */}
      <FormSurface
        mode="drawer"
        title="新增评分规则"
        open={ruleModal}
        onCancel={() => setRuleModal(false)}
        footer={
          <Space>
            <Button onClick={() => setRuleModal(false)}>取消</Button>
            <Button
              type="primary"
              loading={ruleSaving}
              onClick={handleSaveRule}
            >
              保存
            </Button>
          </Space>
        }
      >
        <Form
          form={ruleForm}
          layout="vertical"
          preserve={false}
          initialValues={ruleInit}
        >
          <Form.Item
            name="dimensionName"
            label="评分维度"
            rules={[{ required: true, message: '请输入评分维度' }]}
          >
            <Input
              maxLength={50}
              placeholder="如 仪容仪表 / 演讲内容 / 综合表现"
            />
          </Form.Item>
          {/* 两列等分用 grid：写在 Form.Item 上的 flex:1 会被 antd Space 的 .ant-space-item 吃掉 */}
          <div
            style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}
          >
            <Form.Item
              name="fullMarks"
              label="满分分值"
              rules={[{ required: true, message: '请输入满分分值' }]}
            >
              <InputNumber
                min={1}
                max={1000}
                style={{ width: '100%' }}
                placeholder="如 30"
              />
            </Form.Item>
            <Form.Item name="sortOrder" label="排序">
              <InputNumber min={0} style={{ width: '100%' }} />
            </Form.Item>
          </div>
        </Form>
      </FormSurface>

      {/* 录入记录弹窗 */}
      <FormSurface
        mode="drawer"
        description="选择评分对象与规则，填写分数和说明。"
        title="录入评分记录"
        open={recordModal}
        onCancel={() => setRecordModal(false)}
        footer={
          <Space>
            <Button onClick={() => setRecordModal(false)}>取消</Button>
            <Button
              type="primary"
              loading={recordSaving}
              onClick={handleSaveRecord}
            >
              提交（自动计算总分）
            </Button>
          </Space>
        }
      >
        <Form form={recordForm} layout="vertical" preserve={false}>
          {/* 两列等分用 grid：写在 Form.Item 上的 flex:1 会被 antd Space 的 .ant-space-item 吃掉 */}
          <div
            style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 12 }}
          >
            <Form.Item
              name="teamName"
              label="队名"
              rules={[{ required: true, message: '请输入队名' }]}
            >
              <Input maxLength={100} placeholder="如 计科2301班" />
            </Form.Item>
            <Form.Item name="groupName" label="组别">
              <Input maxLength={100} placeholder="如 第一组" />
            </Form.Item>
          </div>
          <div
            style={{
              fontSize: 14,
              color: 'var(--text-secondary)',
              marginBottom: 8,
            }}
          >
            各维度得分（0 ~ 满分）
          </div>
          {rules.map((rule) => (
            <Form.Item
              key={rule.id}
              name={`dim_${rule.id}`}
              label={`${rule.dimensionName}（满分 ${rule.fullMarks}）`}
            >
              <InputNumber
                min={0}
                max={rule.fullMarks}
                precision={0}
                style={{ width: '100%' }}
                placeholder="0 ~ 满分"
              />
            </Form.Item>
          ))}
          <Form.Item name="remark" label="备注">
            <Input.TextArea rows={2} maxLength={200} />
          </Form.Item>
        </Form>
      </FormSurface>
    </div>
  )
}
