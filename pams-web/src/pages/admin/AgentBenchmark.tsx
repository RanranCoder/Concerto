import { useEffect, useState } from 'react'
import { App, Button, Card, Select, Space, Table, Tag, Typography } from 'antd'
import { ExperimentOutlined } from '@ant-design/icons'
import { listBenchResults, runBench } from '@/api/agentBench'
import type { BenchResult, BenchRow } from '@/api/agentBench'
import { readBenchmarkMetrics } from '@/utils/benchmarkMetrics'

const MODE_OPTIONS = [{ value: 'P1', label: 'P1 顺序' }, { value: 'P2', label: 'P2 依赖并行' }]

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
      const response = await runBench(modes)
      setResult(response)
      message.success(`已采集 ${response.rows.length} 个运行样本，业务质量尚待验收`)
      loadRecent()
    } catch { /* http interceptor reports the error */ } finally { setLoading(false) }
  }
  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
      <Card title={<Space><ExperimentOutlined />单 Agent 与多 Agent 评估</Space>}>
        <Typography.Paragraph>
          使用相同持久化执行内核、任务和权限，比较通才与专家分工。P1 顺序执行，P2 按依赖并行；不同模式分别统计。
          评估只采集预览，待确认操作在采样后取消。正式业务完成率须另以实际回执和预设质量标准验收。
        </Typography.Paragraph>
        <Typography.Paragraph type="secondary">
          仅对显式启用的隔离组织开放。降级、重试和实际模型记入明细；用量缺失及未知价格保持未知，历史记录不补造指标。
          评估可能持续数分钟；离开页面不会取消采样，完成后可刷新查看最近记录。
        </Typography.Paragraph>
        <Space wrap>
          <Select aria-label="评估模式" mode="multiple" value={modes} options={MODE_OPTIONS}
            style={{ minWidth: 220 }} onChange={setModes} />
          <Button type="primary" loading={loading} disabled={!modes.length} onClick={onRun}>运行预览评估</Button>
        </Space>
      </Card>
      {result && <Card title="本次运行统计" extra={<Tag>{result.batchId}</Tag>}>
        <Typography.Paragraph type="secondary">运行成功不代表业务完成；小样本分位数仅描述本次样本。</Typography.Paragraph>
        <Table rowKey="key" size="small" pagination={false} scroll={{ x: 780 }}
          dataSource={Object.entries(result.summary).map(([key, value]) => ({ key, ...value }))}
          columns={[
            { title: '分组', dataIndex: 'key' },
            { title: '样本', dataIndex: 'samples' },
            { title: '运行成功率', dataIndex: 'runSuccessRate', render: (v?: number) => v == null ? '未知' : `${(v * 100).toFixed(1)}%` },
            { title: '待处理样本', dataIndex: 'waiting' },
            { title: 'p50 / p95 耗时', render: (_, row) => `${row.p50DurationMs ?? '未知'} / ${row.p95DurationMs ?? '未知'} ms` },
            { title: '业务质量', render: () => '未评定' },
          ]} />
      </Card>}
      {result && <RowTable title="本次样本" rows={result.rows} />}
      <RowTable title="最近评估记录" rows={recent} />
    </div>
  )
}

function metadata(row: BenchRow): Record<string, unknown> {
  return readBenchmarkMetrics(row.metricsJson)
}
function RowTable({ title, rows }: { title: string; rows: BenchRow[] }) {
  return <Card title={title}><Table<BenchRow> rowKey="id" size="small" dataSource={rows}
    pagination={{ pageSize: 10, showSizeChanger: false }} scroll={{ x: 1100 }} columns={[
      { title: '任务', dataIndex: 'taskCode' },
      { title: '分组', render: (_, row) => `${row.arm} / ${row.mode ?? '历史'}` },
      { title: '采样时状态', dataIndex: 'status', render: (v: string) => <Tag>{v}</Tag> },
      { title: '步骤 / 工具调用', render: (_, row) => `${row.steps} / ${row.toolCalls}` },
      { title: '实际模型', render: (_, row) => {
        const models = metadata(row).models
        return Array.isArray(models) && models.length ? models.join(', ') : '未知'
      } },
      { title: '输入 / 输出 token', render: (_, row) => {
        const data = metadata(row)
        return Number(data.reportedCalls) > 0
          ? `${data.reportedInputTokens} / ${data.reportedOutputTokens}${data.usageComplete ? '' : '（不完整）'}` : '未知'
      } },
      { title: '失败 / 降级', render: (_, row) => {
        const data = metadata(row)
        return data.failures == null ? '未知' : `${data.failures} / ${data.degradedResponses}`
      } },
      { title: '费用', render: () => '未知' },
      { title: '耗时', dataIndex: 'durationMs', render: (v: number | null) => v == null ? '未知' : `${v} ms` },
    ]} /></Card>
}
