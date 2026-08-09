import { useCallback, useEffect, useMemo, useState } from 'react'
import { Button, Empty, message, Select, Space, Spin, Tabs, Tag, Tooltip, Upload } from 'antd'
import { CalendarOutlined, CopyOutlined, DownloadOutlined, FireOutlined, UploadOutlined } from '@ant-design/icons'
import type { UploadFile } from 'antd'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import { listDepts, type DeptVO } from '@/api/dept'
import {
  analyzeFreeTime,
  getMySchedule,
  saveMySchedule,
  getScheduleConfigs,
  importNoClassSchedules,
  downloadNoClassScheduleXlsx,
  type NoClassScheduleImportVO,
  type FreeTimeAnalysisVO,
  type ScheduleConfigVO,
} from '@/api/courseSchedule'
import { listUsers, type UserVO } from '@/api/user'
import { useAuthStore } from '@/stores/auth'

const WEEKDAYS = ['周一', '周二', '周三', '周四', '周五', '周六', '周日']
const SEMESTERS = ['2026-2027-1', '2025-2026-2', '2025-2026-1', '2024-2025-2']

/** 生成 CSV/表格导出 */
function exportMatrixText(analysis: FreeTimeAnalysisVO): string {
  const lines: string[] = []
  lines.push(`共同空闲人数分析（${analysis.semester || '未选学期'}）`)
  lines.push(`参与人数：${analysis.users.length}`)
  lines.push('')
  lines.push(`\t${analysis.periods.map((p) => p.label).join('\t')}`)
  WEEKDAYS.forEach((day, i) => {
    const dayMap = analysis.heatmap[String(i + 1)] ?? {}
    lines.push(
      `${day}\t${analysis.periods
        .map((p) => dayMap[p.period] ?? 0)
        .join('\t')}`,
    )
  })
  lines.push('')
  lines.push('最优时间段（按空闲人数降序）：')
  analysis.optimal.forEach((o, i) => {
    lines.push(`${i + 1}. ${WEEKDAYS[o.dayOfWeek - 1]} ${o.label}：空闲 ${o.freeCount}/${analysis.users.length} 人${o.allFree ? '（全部到齐）' : ''}`)
  })
  return lines.join('\r\n')
}

export default function CourseSchedulePage() {
  const user = useAuthStore((s) => s.user)
  const [tab, setTab] = useState('edit')
  const [semester, setSemester] = useState(SEMESTERS[0])
  const [configs, setConfigs] = useState<ScheduleConfigVO[]>([])
  const [myMatrix, setMyMatrix] = useState<Set<string>>(new Set())
  const [courseNames, setCourseNames] = useState<Record<string, string>>({})
  const [loadingMine, setLoadingMine] = useState(false)
  const [savingMine, setSavingMine] = useState(false)
  const [analysis, setAnalysis] = useState<FreeTimeAnalysisVO | null>(null)
  const [analysisLoading, setAnalysisLoading] = useState(false)
  const [userIds, setUserIds] = useState<number[]>([])
  const [users, setUsers] = useState<UserVO[]>([])

  const isMinister = (user?.roleLevel ?? 0) >= 3
  const [depts, setDepts] = useState<DeptVO[]>([])
  const [impDeptId, setImpDeptId] = useState<number | undefined>(user?.deptId ?? undefined)
  const [impSemester, setImpSemester] = useState('2025-2026-2')
  const [fileList, setFileList] = useState<UploadFile[]>([])
  const [importing, setImporting] = useState(false)
  const [importResult, setImportResult] = useState<NoClassScheduleImportVO | null>(null)

  const loadConfigs = useCallback(() => {
    getScheduleConfigs()
      .then((res) => setConfigs(res ?? []))
      .catch(() => {
        /* 拦截已提示 */
      })
  }, [])

  useEffect(() => {
    loadConfigs()
    listUsers({ size: 1000 })
      .then((res) => setUsers(res.records ?? []))
      .catch(() => {
        /* 拦截已提示 */
      })
    listDepts()
      .then((res) => setDepts(res ?? []))
      .catch(() => {
        /* 拦截已提示 */
      })
  }, [loadConfigs])

  // 加载自己的课程表
  const loadMine = useCallback(() => {
    if (!user?.id) return
    setLoadingMine(true)
    getMySchedule(semester)
      .then((res) => {
        const set = new Set<string>()
        const names: Record<string, string> = {}
        Object.entries(res.matrix ?? {}).forEach(([day, periods]) => {
          Object.entries(periods).forEach(([period, courseName]) => {
            const key = `${day}-${period}`
            set.add(key)
            names[key] = courseName
          })
        })
        setMyMatrix(set)
        setCourseNames(names)
      })
      .catch(() => {
        /* 拦截已提示 */
      })
      .finally(() => setLoadingMine(false))
  }, [semester, user?.id])

  useEffect(() => {
    if (tab === 'edit') loadMine()
  }, [tab, loadMine])

  const toggleCell = (day: number, period: number) => {
    const key = `${day}-${period}`
    const next = new Set(myMatrix)
    if (next.has(key)) {
      next.delete(key)
      const names = { ...courseNames }
      delete names[key]
      setCourseNames(names)
    } else {
      next.add(key)
      const name = window.prompt(`输入课程名称（${WEEKDAYS[day - 1]} 第${period}节，可为空）`, '')
      if (name === null) return // 取消
      setCourseNames((prev) => ({ ...prev, [key]: name }))
    }
    setMyMatrix(next)
  }

  const saveMine = async () => {
    setSavingMine(true)
    try {
      const cells = Array.from(myMatrix).map((key) => {
        const [day, period] = key.split('-').map(Number)
        return { dayOfWeek: day, period, courseName: courseNames[key] || undefined }
      })
      await saveMySchedule(semester, cells)
      message.success('课程表已保存')
      loadMine()
    } catch {
      /* 拦截已提示 */
    } finally {
      setSavingMine(false)
    }
  }

  // 生成热力图
  const runAnalysis = async () => {
    setAnalysisLoading(true)
    try {
      const res = await analyzeFreeTime(semester, userIds.length > 0 ? userIds : undefined)
      setAnalysis(res)
    } catch {
      /* 拦截已提示 */
    } finally {
      setAnalysisLoading(false)
    }
  }

  // 导出
  const exportResult = () => {
    if (!analysis) return
    const text = exportMatrixText(analysis)
    const blob = new Blob(['\ufeff' + text], { type: 'text/plain;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `无课表分析_${semester || '未选学期'}.txt`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  const runImport = async () => {
    if (fileList.length === 0) {
      message.warning('请先选择课表文件')
      return
    }
    const formData = new FormData()
    fileList.forEach((f) => {
      if (f.originFileObj) formData.append('files', f.originFileObj, f.name)
    })
    if (impDeptId) formData.append('deptId', String(impDeptId))
    formData.append('semester', impSemester)
    setImporting(true)
    try {
      const res = await importNoClassSchedules(formData)
      setImportResult(res)
      message.success(`成功生成 ${res.successCount}/${res.totalFiles} 份课表`)
    } catch {
      /* 拦截已提示 */
    } finally {
      setImporting(false)
    }
  }

  const handleDownloadXlsx = async () => {
    if (!importResult?.downloadUrl) return
    const res = await downloadNoClassScheduleXlsx(importResult.downloadUrl)
    const url = URL.createObjectURL(res.data)
    const a = document.createElement('a')
    a.href = url
    a.download = `无课表_${importResult.deptName}_${importResult.semester}.xlsx`
    document.body.appendChild(a)
    a.click()
    document.body.removeChild(a)
    URL.revokeObjectURL(url)
  }

  const copyMarkdown = async () => {
    if (!importResult) return
    await navigator.clipboard.writeText(importResult.markdown)
    message.success('已复制 Markdown')
  }

  const maxFree = useMemo(() => {
    if (!analysis) return 0
    let m = 0
    Object.values(analysis.heatmap).forEach((dayMap) => {
      Object.values(dayMap).forEach((v) => {
        if (v > m) m = v
      })
    })
    return Math.max(m, 1)
  }, [analysis])

  const heatColor = (free: number) => {
    const ratio = free / maxFree
    // 深色 = 空闲人数多
    if (ratio >= 0.9) return 'var(--color-primary)'
    if (ratio >= 0.7) return 'var(--color-primary-weak, #4096ff)'
    if (ratio >= 0.5) return 'rgba(64,150,255,.45)'
    if (ratio >= 0.3) return 'rgba(64,150,255,.22)'
    return 'rgba(148,158,175,.12)'
  }

  const editTab = (
    <div>
      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <span style={{ fontWeight: 600 }}>我的课程表</span>
          <Select value={semester} onChange={setSemester} style={{ width: 200 }} options={SEMESTERS.map((s) => ({ value: s, label: s }))} />
          <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
            点击时间格标记有课（只能编辑自己的课程表）
          </span>
        </Space>
      </GlassCard>
      <GlassCard style={{ padding: 16 }}>
        <Spin spinning={loadingMine}>
          <div style={{ overflow: 'auto' }}>
            <table style={{ borderCollapse: 'collapse', fontSize: 13, minWidth: 720 }}>
              <thead>
                <tr>
                  <th style={{ padding: '6px 10px', border: '1px solid var(--color-border)', background: 'var(--color-bg-2)' }}>节次</th>
                  {WEEKDAYS.map((d) => (
                    <th key={d} style={{ padding: '6px 10px', border: '1px solid var(--color-border)', background: 'var(--color-bg-2)' }}>
                      {d}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {(configs.length > 0 ? configs : Array.from({ length: 5 }, (_, i) => ({ id: i + 1, period: i + 1, label: `第${i * 2 + 1}-${i * 2 + 2}节`, startTime: null, endTime: null }))).map((cfg) => (
                  <tr key={cfg.period}>
                    <td style={{ padding: '6px 10px', border: '1px solid var(--color-border)', whiteSpace: 'nowrap', color: 'var(--color-text-secondary)' }}>
                      {cfg.label}
                      {cfg.startTime ? <span style={{ fontSize: 11 }}> {cfg.startTime.slice(0, 5)}-{cfg.endTime?.slice(0, 5)}</span> : null}
                    </td>
                    {WEEKDAYS.map((_, di) => {
                      const day = di + 1
                      const key = `${day}-${cfg.period}`
                      const has = myMatrix.has(key)
                      return (
                        <td
                          key={di}
                          onClick={() => toggleCell(day, cfg.period)}
                          style={{
                            padding: '6px 10px',
                            border: '1px solid var(--color-border)',
                            textAlign: 'center',
                            cursor: 'pointer',
                            background: has ? 'var(--color-primary)' : 'transparent',
                            color: has ? '#fff' : 'var(--color-text-secondary)',
                            minWidth: 80,
                          }}
                        >
                          {has ? (courseNames[key] || '有课') : '空'}
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div style={{ marginTop: 12 }}>
            <Button type="primary" onClick={saveMine} loading={savingMine}>
              保存课程表
            </Button>
            <span style={{ marginLeft: 12, color: 'var(--color-text-secondary)', fontSize: 12 }}>
              已标记 {myMatrix.size} 个时间格
            </span>
          </div>
        </Spin>
      </GlassCard>
    </div>
  )

  const analyzeTab = (
    <div>
      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <span style={{ fontWeight: 600 }}>共同空闲分析</span>
          <Select value={semester} onChange={setSemester} style={{ width: 200 }} options={SEMESTERS.map((s) => ({ value: s, label: s }))} />
          <Select
            mode="multiple"
            allowClear
            style={{ minWidth: 260 }}
            placeholder="选择参与人员（默认全员）"
            value={userIds}
            onChange={setUserIds}
            options={users.map((u) => ({ value: u.id, label: `${u.realName}（${u.deptName ?? ''}）` }))}
            maxTagCount="responsive"
          />
          <Button type="primary" icon={<FireOutlined />} onClick={runAnalysis} loading={analysisLoading}>
            生成热力图
          </Button>
          {analysis && (
            <Button icon={<DownloadOutlined />} onClick={exportResult}>
              导出
            </Button>
          )}
        </Space>
      </GlassCard>

      {!analysis ? (
        <GlassCard style={{ padding: 40, textAlign: 'center' }}>
          <Empty description="选择学期与人员后点击「生成热力图」，颜色越深表示空闲人数越多" />
        </GlassCard>
      ) : (
        <GlassCard style={{ padding: 16 }}>
          <div style={{ marginBottom: 12, fontSize: 13, color: 'var(--color-text-secondary)' }}>
            参与 {analysis.users.length} 人 · {analysis.semester || '未选学期'}
            <Tag style={{ marginLeft: 8 }} color="blue">
              AI 推荐：{analysis.optimal[0] ? `${WEEKDAYS[analysis.optimal[0].dayOfWeek - 1]} ${analysis.optimal[0].label}（${analysis.optimal[0].freeCount}/${analysis.users.length} 人空闲）` : '-'}
            </Tag>
          </div>
          <div style={{ overflow: 'auto' }}>
            <table style={{ borderCollapse: 'collapse', fontSize: 13, minWidth: 720 }}>
              <thead>
                <tr>
                  <th style={{ padding: '6px 10px', border: '1px solid var(--color-border)', background: 'var(--color-bg-2)' }}>节次</th>
                  {WEEKDAYS.map((d) => (
                    <th key={d} style={{ padding: '6px 10px', border: '1px solid var(--color-border)', background: 'var(--color-bg-2)' }}>
                      {d}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {analysis.periods.map((cfg) => (
                  <tr key={cfg.period}>
                    <td style={{ padding: '6px 10px', border: '1px solid var(--color-border)', whiteSpace: 'nowrap', color: 'var(--color-text-secondary)' }}>
                      {cfg.label}
                    </td>
                    {WEEKDAYS.map((_, di) => {
                      const day = di + 1
                      const free = (analysis.heatmap[String(day)] ?? {})[cfg.period] ?? 0
                      const allFree = free === analysis.users.length
                      return (
                        <Tooltip key={di} title={`${WEEKDAYS[di]} ${cfg.label}：${free}/${analysis.users.length} 人空闲${allFree ? '（全部到齐）' : ''}`}>
                          <td
                            style={{
                              padding: '10px',
                              border: '1px solid var(--color-border)',
                              textAlign: 'center',
                              background: heatColor(free),
                              color: free / maxFree >= 0.9 ? '#fff' : 'var(--color-text)',
                              fontWeight: allFree ? 700 : 400,
                              minWidth: 80,
                            }}
                          >
                            {free}
                          </td>
                        </Tooltip>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <div style={{ marginTop: 12, display: 'flex', alignItems: 'center', gap: 8, fontSize: 12, color: 'var(--color-text-secondary)' }}>
            图例：
            {[0.1, 0.3, 0.5, 0.7, 1].map((r) => (
              <span key={r} style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
                <span style={{ width: 16, height: 16, borderRadius: 4, background: heatColor(r * maxFree) }} />
                {r === 1 ? '全空闲' : `${Math.round(r * 100)}%`}
              </span>
            ))}
          </div>

          {analysis.optimal.length > 0 && (
            <div style={{ marginTop: 16 }}>
              <div style={{ fontWeight: 600, marginBottom: 8 }}>最优时间段推荐</div>
              {analysis.optimal.slice(0, 5).map((o, i) => (
                <div key={i} style={{ padding: '6px 0', borderBottom: '1px dashed var(--color-border)', fontSize: 13 }}>
                  <span style={{ marginRight: 8 }}>{i + 1}.</span>
                  {WEEKDAYS[o.dayOfWeek - 1]} {o.label} —— 空闲 {o.freeCount}/{analysis.users.length} 人
                  {o.allFree && <Tag color="red" style={{ marginLeft: 8 }}>全部到齐</Tag>}
                </div>
              ))}
            </div>
          )}
        </GlassCard>
      )}
    </div>
  )

  const importTab = (
    <div>
      <GlassCard style={{ padding: 16, marginBottom: 16 }}>
        <Space wrap>
          <span style={{ fontWeight: 600 }}>批量导入课表</span>
          <Select
            placeholder="部门"
            style={{ width: 180 }}
            value={impDeptId}
            onChange={setImpDeptId}
            options={depts.map((d) => ({ value: d.id, label: d.name }))}
          />
          <Select
            value={impSemester}
            onChange={setImpSemester}
            style={{ width: 180 }}
            options={SEMESTERS.map((s) => ({ value: s, label: s }))}
          />
          <Upload
            multiple
            accept=".xlsx,.xls"
            fileList={fileList}
            beforeUpload={(file) => {
              setFileList((prev) => [...prev, file])
              return false
            }}
            onRemove={(file) => setFileList((prev) => prev.filter((f) => f.uid !== file.uid))}
          >
            <Button icon={<UploadOutlined />}>选择课表文件（可多选）</Button>
          </Upload>
          <Button type="primary" icon={<FireOutlined />} onClick={runImport} loading={importing}>
            生成无课表
          </Button>
          {importResult && (
            <>
              <Button icon={<DownloadOutlined />} onClick={handleDownloadXlsx}>
                下载 Excel
              </Button>
              <Button icon={<CopyOutlined />} onClick={copyMarkdown}>
                复制 Markdown
              </Button>
            </>
          )}
        </Space>
        {fileList.length > 0 && (
          <div style={{ marginTop: 8, color: 'var(--color-text-secondary)', fontSize: 12 }}>
            已选 {fileList.length} 个文件，文件名为「姓名-班级-班级课表.xlsx」时自动识别姓名
          </div>
        )}
      </GlassCard>

      {importResult && (
        <GlassCard style={{ padding: 16 }}>
          <div style={{ marginBottom: 12, fontSize: 13, color: 'var(--color-text-secondary)' }}>
            {importResult.deptName} · {importResult.semester} · 成功 {importResult.successCount}/{importResult.totalFiles}
            {importResult.failed.length > 0 && (
              <span style={{ color: 'var(--color-red)', marginLeft: 8 }}>
                失败 {importResult.failed.length}：
                {importResult.failed.map((f) => `${f.fileName}（${f.reason}）`).join('；')}
              </span>
            )}
          </div>
          {importResult.warnings.map((w) => (
            <div key={w} style={{ fontSize: 12, color: '#d48806', marginBottom: 4 }}>
              {w}
            </div>
          ))}
          <div style={{ overflow: 'auto' }}>
            <table style={{ borderCollapse: 'collapse', fontSize: 13, minWidth: 720 }}>
              <thead>
                <tr>
                  <th style={{ padding: '6px 10px', border: '1px solid var(--color-border)', background: 'var(--color-bg-2)' }}>节次</th>
                  {WEEKDAYS.slice(0, 5).map((d) => (
                    <th key={d} style={{ padding: '6px 10px', border: '1px solid var(--color-border)', background: 'var(--color-bg-2)' }}>
                      {d}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {importResult.rows.map((row) => (
                  <tr key={row.period}>
                    <td style={{ padding: '6px 10px', border: '1px solid var(--color-border)', whiteSpace: 'nowrap', color: 'var(--color-text-secondary)' }}>
                      {row.label}
                    </td>
                    {[1, 2, 3, 4, 5].map((day) => (
                      <td key={day} style={{ padding: '6px 10px', border: '1px solid var(--color-border)', verticalAlign: 'top', minWidth: 140 }}>
                        {(row.days[String(day)] ?? []).map((c) => (
                          <div key={c.name}>
                            {c.name}（{c.freeWeeks}）
                          </div>
                        ))}
                      </td>
                    ))}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </GlassCard>
      )}
    </div>
  )

  return (
    <div>
      <PageHeader
        title="无课表制作"
        description="录入个人课程表，AI 计算共同空闲时间，生成热力图推荐最佳会议/排班时段"
        extra={
          <Space>
            <CalendarOutlined style={{ color: 'var(--color-text-secondary)' }} />
            <span style={{ color: 'var(--color-text-secondary)', fontSize: 12 }}>
              当前用户：{user?.realName}
            </span>
          </Space>
        }
      />
      <Tabs
        activeKey={tab}
        onChange={setTab}
        items={[
          { key: 'edit', label: '我的课程表', children: editTab },
          { key: 'analyze', label: '共同空闲分析', children: analyzeTab },
          ...(isMinister ? [{ key: 'import', label: '批量导入', children: importTab }] : []),
        ]}
      />
    </div>
  )
}
