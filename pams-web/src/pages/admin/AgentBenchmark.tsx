import { useEffect, useState } from 'react'
import { App, Button, Card, Descriptions, Select, Space, Statistic, Table, Tag, Typography } from 'antd'
import { ExperimentOutlined } from '@ant-design/icons'
import { listBenchResults, runBench } from '@/api/agentBench'
import type { BenchResult, BenchRow } from '@/api/agentBench'

const MODE_OPTIONS = ['P1', 'P2', 'P3', 'P4'].map((m) => ({ value: m, label: m }))

export default function AgentBenchmark() {
  const { message } = App.useApp()
  const [modes, setModes] = useState<string[]>(['P1'])
  const [loading, setLoading] = useState(false)
  const [result, setResult] = useState<BenchResult | null>(null)
  const [recent, setRecent] = useState<BenchRow[]>([])

  const loadRecent = () => listBenchResults().then(setRecent).catch(() => {})
  useEffect(() => { loadRecent() }, [])

  const onRun = async () => {
    setLoading(true)
    try {
      const res = await runBench(modes)
      setResult(res)
      message.success(`实验完成：${res.tasks} 个任务 × ${modes.length + 1} 臂`)
      loadRecent()
    } catch { /* http 拦截已提示 */ } finally { setLoading(false) }
  }

  const single = result?.summary?.SINGLE
  const multi = result?.summary?.MULTI

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Card
        title={<Space><ExperimentOutlined />对比评估实验（单 Agent vs 多 Agent）</Space>}
        extra={
          <Space>
            <Select mode="multiple" value={modes} options={MODE_OPTIONS} style={{ minWidth: 160 }}
              onChange={setModes} placeholder="多 Agent 臂的模式" />
            <Button type="primary" loading={loading} onClick={onRun}>运行实验</Button>
          </Space>
        }
      >
        <Typography.Paragraph type="secondary" style={{ marginBottom: 8 }}>
          两条臂共用同一引擎（专家执行内核 + 工具注册表 + LLM），唯一变量是「是否拆分协作」：
          单 Agent 臂 = 一个通才 Agent（工具不限）；多 Agent 臂 = 协调者规划 + 专家 + 审查。
          内置 5 类任务（检索 / 策划 / 文书 / 排班 / 事务）。
        </Typography.Paragraph>
        <Typography.Paragraph type="warning" style={{ marginBottom: 0, fontSize: 13 }}>
          注：离线（未配置 LLM Key）下专家不产出工具调用，内容质量类指标（工具调用数、审查通过率）会退化；
          真实对比请在有 Key 的环境运行。
        </Typography.Paragraph>
      </Card>

      {result && single && multi && (
        <Card title="本次结果对比" extra={<Tag>batch {result.batchId}</Tag>}>
          <Space size={48} wrap style={{ marginBottom: 16 }}>
            <Statistic title="SINGLE 完成率" value={single.completionRate * 100} suffix="%" precision={1} />
            <Statistic title="SINGLE 平均步骤" value={single.avgSteps} />
            <Statistic title="SINGLE 平均耗时" value={single.avgDurationMs} suffix="ms" />
            <Statistic title="MULTI 完成率" value={multi.completionRate * 100} suffix="%" precision={1} />
            <Statistic title="MULTI 平均步骤" value={multi.avgSteps} />
            <Statistic title="MULTI 平均耗时" value={multi.avgDurationMs} suffix="ms" />
          </Space>

          <BarGroup label="平均步骤数" single={single.avgSteps} multi={multi.avgSteps} />
          <BarGroup label="参与 Agent 数" single={single.avgAgents} multi={multi.avgAgents} />
          <BarGroup label="工具调用数" single={single.avgToolCalls} multi={multi.avgToolCalls} />
          <BarGroup label="耗时 (ms)" single={single.avgDurationMs} multi={multi.avgDurationMs} />

          <Descriptions size="small" column={2} style={{ marginTop: 12 }}
            items={[
              { key: 's', label: 'SINGLE 样本', children: single.samples },
              { key: 'm', label: 'MULTI 样本', children: multi.samples },
              { key: 'sr', label: 'SINGLE 产物数', children: single.avgArtifacts },
              { key: 'mr', label: 'MULTI 审查通过率', children: multi.reviewPassRate == null ? '—' : `${(multi.reviewPassRate * 100).toFixed(1)}%` },
            ]} />
        </Card>
      )}

      {result && <RowTable title="本次明细" rows={result.rows} />}
      <RowTable title="最近实验记录" rows={recent} />
    </div>
  )
}

function BarGroup({ label, single, multi }: { label: string; single: number; multi: number }) {
  const max = Math.max(single, multi, 1)
  return (
    <div style={{ marginBottom: 8 }}>
      <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginBottom: 4 }}>{label}</div>
      <Bar color="var(--text-tertiary)" value={single} max={max} tag={`SINGLE ${fmt(single)}`} />
      <Bar color="var(--brand)" value={multi} max={max} tag={`MULTI ${fmt(multi)}`} />
    </div>
  )
}

function Bar({ color, value, max, tag }: { color: string; value: number; max: number; tag: string }) {
  const pct = Math.max(2, Math.round((value / max) * 100))
  return (
    <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
      <div style={{ flex: 1, height: 14, background: 'var(--soft)', borderRadius: 4, overflow: 'hidden' }}>
        <div style={{ width: `${pct}%`, height: '100%', background: color, borderRadius: 4 }} />
      </div>
      <span style={{ width: 130, fontSize: 13, color: 'var(--text-secondary)', fontFamily: 'monospace' }}>{tag}</span>
    </div>
  )
}

function fmt(v: number) {
  return Number.isInteger(v) ? String(v) : v.toFixed(2)
}

function RowTable({ title, rows }: { title: string; rows: BenchRow[] }) {
  return (
    <Card title={title}>
      <Table<BenchRow>
        rowKey="id"
        size="small"
        dataSource={rows}
        pagination={{ pageSize: 10, showSizeChanger: false }}
        scroll={{ x: 900 }}
        columns={[
          { title: '任务', dataIndex: 'taskCode', width: 110 },
          { title: '臂', dataIndex: 'arm', width: 90, render: (v: string) => <Tag color={v === 'SINGLE' ? 'default' : 'red'}>{v}</Tag> },
          { title: '模式', dataIndex: 'mode', width: 70 },
          { title: '状态', dataIndex: 'status', width: 90, render: (v: string) => <Tag color={v === 'SUCCESS' ? 'green' : 'orange'}>{v}</Tag> },
          { title: '步骤', dataIndex: 'steps', width: 70 },
          { title: 'Agent数', dataIndex: 'agents', width: 80 },
          { title: '工具调用', dataIndex: 'toolCalls', width: 90 },
          { title: '审查', dataIndex: 'reviewPass', width: 80, render: (v: number | null) => v == null ? '—' : (v === 1 ? <Tag color="green">PASS</Tag> : <Tag color="orange">REVISE</Tag>) },
          { title: '耗时(ms)', dataIndex: 'durationMs', width: 100 },
        ]}
      />
    </Card>
  )
}
