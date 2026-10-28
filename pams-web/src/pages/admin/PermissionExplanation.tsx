import { useEffect, useRef, useState } from 'react'
import { Alert, Button, Input, Select } from 'antd'
import { get } from '@/api/http'

const operations = [
  { value: 'plan:review', label: '审核指定策划', object: '策划版本编号' },
  { value: 'schedule:publish', label: '发布排班', object: '班次编号' },
  { value: 'attendance:manage', label: '人工修正考勤', object: '考勤记录编号' },
  { value: 'file:view', label: '读取文件', object: '文件编号' },
]
export default function PermissionExplanation({ userId }: { userId: number }) {
  const [operation, setOperation] = useState(operations[0].value)
  const [resourceId, setResourceId] = useState('')
  const [result, setResult] = useState<{ allowed: boolean; reason: string }>()
  const [loading, setLoading] = useState(false)
  const sequence = useRef(0)
  useEffect(() => {
    sequence.current++
    setResult(undefined)
    setLoading(false)
  }, [userId, operation, resourceId])
  const check = async () => {
    const request = ++sequence.current
    setLoading(true)
    try {
      const next = await get<{ allowed: boolean; reason: string }>(
        `/users/${userId}/permission-explanation`,
        { operation, resourceId: Number(resourceId) },
      )
      if (sequence.current === request) setResult(next)
    } catch {
      if (sequence.current === request)
        setResult({ allowed: false, reason: '校验未完成，请重试。' })
    } finally {
      if (sequence.current === request) setLoading(false)
    }
  }
  return (
    <section>
      <h3>核对具体操作</h3>
      <p className="permission-caption">
        仅解释是否允许，不展示业务正文，也不会执行操作。
      </p>
      <div className="permission-toolbar">
        <Select
          aria-label="核对操作"
          value={operation}
          options={operations}
          onChange={setOperation}
        />
        <Input
          aria-label={operations.find((o) => o.value === operation)?.object}
          type="number"
          min={1}
          placeholder={operations.find((o) => o.value === operation)?.object}
          value={resourceId}
          onChange={(e) => setResourceId(e.target.value)}
        />
        <Button
          loading={loading}
          disabled={!/^\d+$/.test(resourceId) || Number(resourceId) < 1}
          onClick={() => void check()}
        >
          核对权限
        </Button>
      </div>
      {result && (
        <Alert
          type={result.allowed ? 'success' : 'info'}
          showIcon
          message={result.allowed ? '当前允许此操作' : '当前不能执行此操作'}
          description={result.reason}
        />
      )}
    </section>
  )
}
