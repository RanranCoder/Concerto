import FormSurface from '@/components/form/FormSurface'
import { useEffect, useState } from 'react'
import {
  App,
  Button,
  Empty,
  Input,
  Select,
  Space,
  Spin,
  Tabs,
  Upload,
  type UploadFile,
} from 'antd'
import {
  CopyOutlined,
  DeleteOutlined,
  DownloadOutlined,
  InboxOutlined,
  UploadOutlined,
} from '@ant-design/icons'
import { useNavigate, useSearchParams } from 'react-router-dom'
import dayjs from 'dayjs'
import GlassCard from '@/components/glass/GlassCard'
import PageHeader from '@/components/glass/PageHeader'
import RoutineLayout, { RoutineNote } from '@/components/routine/RoutineLayout'
import FreeScheduleGrid from '@/components/routine/FreeScheduleGrid'
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
import { listSemesters } from '@/api/activitySchedule'
import { listDepts, type DeptVO } from '@/api/dept'
import { listUserOptions, type UserOptionVO } from '@/api/user'
import { useAuthStore } from '@/stores/auth'
import {
  FALLBACK_PERIODS,
  WEEK_DAYS,
  downloadBlob,
  downloadCsv,
} from '@/utils/routine'

const now = dayjs(),
  academicYear = now.month() >= 7 ? now.year() : now.year() - 1
const currentSemester = `${academicYear}-${academicYear + 1}-${now.month() >= 7 ? 1 : 2}`
const DEFAULT_SEMESTERS = [
  currentSemester,
  `${academicYear}-${academicYear + 1}-1`,
  `${academicYear - 1}-${academicYear}-2`,
  `${academicYear - 1}-${academicYear}-1`,
]
export default function CourseSchedulePage() {
  const { message } = App.useApp(),
    navigate = useNavigate(),
    [params, setParams] = useSearchParams(),
    user = useAuthStore((s) => s.user)
  const canManage = user?.permissions.includes('schedule:manage'),
    canEditCourse = user?.permissions.includes('schedule:free_table')
  const [tab, setTab] = useState(
      params.get('tab') || (canManage ? 'import' : 'edit'),
    ),
    [semester, setSemester] = useState(
      params.get('semester') || currentSemester,
    ),
    [semesterOptions, setSemesterOptions] =
      useState<string[]>(DEFAULT_SEMESTERS),
    [pendingSemester, setPendingSemester] = useState<string | null>(null)
  const [configs, setConfigs] = useState<ScheduleConfigVO[]>(FALLBACK_PERIODS),
    [matrix, setMatrix] = useState<Record<string, string>>({}),
    [loading, setLoading] = useState(false),
    [mineLoaded, setMineLoaded] = useState(false),
    [saving, setSaving] = useState(false),
    [dirty, setDirty] = useState(false),
    [reload, setReload] = useState(0)
  const [cell, setCell] = useState<{
      day: number
      period: number
      label: string
    } | null>(null),
    [cellName, setCellName] = useState('')
  const [users, setUsers] = useState<UserOptionVO[]>([]),
    [userIds, setUserIds] = useState<number[]>([]),
    [analysis, setAnalysis] = useState<FreeTimeAnalysisVO | null>(null),
    [analyzing, setAnalyzing] = useState(false),
    [selectedSlot, setSelectedSlot] = useState<{
      day: number
      period: number
      label: string
    } | null>(null)
  const [depts, setDepts] = useState<DeptVO[]>([]),
    [impDeptId, setImpDeptId] = useState<number | undefined>(
      Number(params.get('deptId')) || user?.deptId || undefined,
    ),
    [impSemester, setImpSemester] = useState(
      params.get('semester') || currentSemester,
    )
  const [files, setFiles] = useState<UploadFile[]>([]),
    [result, setResult] = useState<NoClassScheduleImportVO | null>(null),
    [importing, setImporting] = useState(false),
    [confirmImport, setConfirmImport] = useState(false)
  const periods = configs.length ? configs : FALLBACK_PERIODS,
    allSemesters = [...new Set([semester, impSemester, ...semesterOptions])]
  useEffect(() => {
    getScheduleConfigs()
      .then((r) => {
        if (r?.length) setConfigs(r)
      })
      .catch(() => {})
    listUserOptions()
      .then(setUsers)
      .catch(() => {})
    listDepts()
      .then(setDepts)
      .catch(() => {})
    listSemesters()
      .then((r) =>
        setSemesterOptions((s) => [...new Set([...(r ?? []), ...s])]),
      )
      .catch(() => {})
  }, [])
  useEffect(() => {
    let current = true
    setLoading(true)
    setMineLoaded(false)
    setMatrix({})
    getMySchedule(semester)
      .then((r) => {
        if (!current) return
        const next: Record<string, string> = {}
        Object.entries(r.matrix ?? {}).forEach(([day, ps]) =>
          Object.entries(ps).forEach(([p, name]) => {
            next[`${day}-${p}`] = name ?? ''
          }),
        )
        setMatrix(next)
        setDirty(false)
        setMineLoaded(true)
      })
      .catch(() => {})
      .finally(() => {
        if (current) setLoading(false)
      })
    return () => {
      current = false
    }
  }, [semester, reload])
  const chooseSemester = (s: string) => {
    if (dirty) setPendingSemester(s)
    else {
      setSemester(s)
      setAnalysis(null)
      setSelectedSlot(null)
    }
  }
  const saveMine = async () => {
    setSaving(true)
    try {
      await saveMySchedule(
        semester,
        Object.entries(matrix).map(([key, courseName]) => {
          const [dayOfWeek, period] = key.split('-').map(Number)
          return { dayOfWeek, period, courseName: courseName || undefined }
        }),
      )
      setDirty(false)
      setAnalysis(null)
      message.success('课程表已保存，可重新分析共同空闲')
    } catch {
    } finally {
      setSaving(false)
    }
  }
  const applyCell = (courseName?: string, clear = false) => {
    if (!cell) return
    const key = `${cell.day}-${cell.period}`
    setMatrix((prev) => {
      const next = { ...prev }
      if (clear) delete next[key]
      else next[key] = courseName?.trim() || ''
      return next
    })
    setDirty(true)
    setCell(null)
  }
  const runAnalysis = async () => {
    setAnalyzing(true)
    try {
      const r = await analyzeFreeTime(
        semester,
        userIds.length ? userIds : undefined,
      )
      setAnalysis(r)
      const optimal = r.optimal[0]
      setSelectedSlot(
        optimal
          ? {
              day: optimal.dayOfWeek,
              period: optimal.period,
              label: optimal.label,
            }
          : null,
      )
    } catch {
    } finally {
      setAnalyzing(false)
    }
  }
  const runImport = async () => {
    if (!impDeptId || !files.length) return
    setImporting(true)
    try {
      const data = new FormData()
      files.forEach((f) => {
        const raw = f.originFileObj ?? (f as unknown as File)
        data.append('files', raw, f.name)
      })
      data.append('deptId', String(impDeptId))
      data.append('semester', impSemester)
      const imported = await importNoClassSchedules(data)
      setResult(imported)
      setConfirmImport(false)
      message.success(
        `已生成无课表：${imported.successCount}/${imported.totalFiles} 份成功`,
      )
    } catch {
    } finally {
      setImporting(false)
    }
  }
  const downloadXlsx = async () => {
    if (!result?.downloadUrl) return
    try {
      const r = await downloadNoClassScheduleXlsx(result.downloadUrl)
      downloadBlob(r.data, `${result.deptName}-${result.semester}-无课表.xlsx`)
    } catch {}
  }
  const copyMarkdown = async () => {
    if (!result) return
    try {
      if (navigator.clipboard?.writeText)
        await navigator.clipboard.writeText(result.markdown)
      else {
        const area = document.createElement('textarea')
        area.value = result.markdown
        area.style.position = 'fixed'
        area.style.opacity = '0'
        document.body.appendChild(area)
        area.select()
        const copied = document.execCommand('copy')
        area.remove()
        if (!copied) throw new Error('copy')
      }
      message.success('已复制 Markdown')
    } catch {
      message.warning('复制失败，请使用下载 Excel 保存结果')
    }
  }
  const setTabWithUrl = (key: string) => {
    setTab(key)
    const p = new URLSearchParams(params)
    p.set('tab', key)
    setParams(p, { replace: true })
  }
  const semesterControl = (
    <label className="routine-filter">
      学期
      <Select
        aria-label="课程表学期"
        disabled={saving || analyzing}
        value={semester}
        onChange={chooseSemester}
        options={allSemesters.map((s) => ({ value: s, label: s }))}
      />
    </label>
  )
  const editPanel = (
    <GlassCard>
      <div className="routine-toolbar">
        {semesterControl}
        <span className="routine-muted">
          {user?.realName} / {Object.keys(matrix).length} 个有课时段
        </span>
        <div className="routine-toolbar-actions">
          <span
            className={`tone-tag ${dirty ? 'tone-warning' : 'tone-success'}`}
          >
            {dirty ? '有未保存修改' : '已保存'}
          </span>
          <Button
            type="primary"
            disabled={!canEditCourse || !mineLoaded || loading}
            loading={saving}
            onClick={saveMine}
          >
            保存课程表
          </Button>
        </div>
      </div>
      <div className="routine-caption">
        <span>点击空时段添加课程；点击有课时段编辑或清除</span>
        <span>蓝色 = 有课 / 灰色 = 无课</span>
      </div>
      <Spin spinning={loading}>
        {mineLoaded ? (
          <div className="routine-table-scroll">
            <table className="routine-week-table routine-course-table">
              <thead>
                <tr>
                  <th scope="col">节次 / 时间</th>
                  {WEEK_DAYS.map((d) => (
                    <th scope="col" key={d}>
                      {d}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {periods.map((p) => (
                  <tr key={p.period}>
                    <th scope="row">
                      {p.label}
                      <small>
                        {p.startTime &&
                          `${p.startTime.slice(0, 5)}–${p.endTime?.slice(0, 5) ?? ''}`}
                      </small>
                    </th>
                    {WEEK_DAYS.map((d, di) => {
                      const key = `${di + 1}-${p.period}`,
                        busy = Object.hasOwn(matrix, key)
                      return (
                        <td key={d}>
                          <button
                            disabled={saving || !canEditCourse}
                            type="button"
                            aria-pressed={busy}
                            className={`routine-course-cell${busy ? ' active' : ''}`}
                            onClick={() => {
                              setCellName(matrix[key] ?? '')
                              setCell({
                                day: di + 1,
                                period: p.period,
                                label: p.label,
                              })
                            }}
                          >
                            <strong>
                              {busy ? matrix[key] || '有课' : '无课'}
                            </strong>
                            {busy ? '点击编辑课程' : '＋ 标记有课'}
                          </button>
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          !loading && (
            <div className="routine-empty">
              <Empty description="课程表读取失败，请重试后再编辑">
                <Button onClick={() => setReload((n) => n + 1)}>
                  重新读取
                </Button>
              </Empty>
            </div>
          )
        )}
      </Spin>
      <div className="routine-table-foot">
        <span>个人课程表不区分单双周</span>
        <Button type="link" onClick={() => setTabWithUrl('analyze')}>
          分析共同空闲
        </Button>
      </div>
    </GlassCard>
  )
  const heatmapPanel = (
    <div className="routine-workspace">
      <GlassCard>
        <div className="routine-pad">
          <h3>选择参与人员</h3>
          <Select
            aria-label="分析参与人员"
            style={{ width: '100%' }}
            mode="multiple"
            showSearch
            optionFilterProp="label"
            allowClear
            placeholder="选择参与人员（留空分析全员）"
            value={userIds}
            disabled={analyzing}
            onChange={(ids) => {
              setUserIds(ids)
              setAnalysis(null)
              setSelectedSlot(null)
            }}
            options={users.map((u) => ({
              value: u.id,
              label: `${u.realName}（${u.deptName ?? ''}）`,
            }))}
          />
          <RoutineNote>
            使用成员保存的个人课程表分析共同空闲，不读取批量导入的周次无课表。
            {dirty &&
              '当前个人课程表有未保存修改，本次分析将使用上次保存的数据。'}
          </RoutineNote>
        </div>
        <div className="routine-toolbar">
          {semesterControl}
          <Button type="primary" loading={analyzing} onClick={runAnalysis}>
            分析共同空闲
          </Button>
          {analysis && (
            <Button
              icon={<DownloadOutlined />}
              onClick={() =>
                downloadCsv('共同空闲分析', [
                  ['节次', ...WEEK_DAYS],
                  ...analysis.periods.map((p) => [
                    p.label,
                    ...WEEK_DAYS.map(
                      (_, i) =>
                        analysis.heatmap[String(i + 1)]?.[p.period] ?? 0,
                    ),
                  ]),
                ])
              }
            >
              导出结果
            </Button>
          )}
        </div>
        {analysis ? (
          <>
            <div className="routine-caption">
              <span>参与 {analysis.users.length} 人 / 点击时段查看人数</span>
              <span>青绿 = 全员无课</span>
            </div>
            <div className="routine-table-scroll">
              <table className="routine-week-table routine-course-table">
                <thead>
                  <tr>
                    <th>节次 / 时间</th>
                    {WEEK_DAYS.map((d) => (
                      <th key={d}>{d}</th>
                    ))}
                  </tr>
                </thead>
                <tbody>
                  {analysis.periods.map((p) => (
                    <tr key={p.period}>
                      <th>
                        {p.label}
                        <small>
                          {p.startTime?.slice(0, 5)}
                          {p.endTime && `–${p.endTime.slice(0, 5)}`}
                        </small>
                      </th>
                      {WEEK_DAYS.map((d, di) => {
                        const free =
                            analysis.heatmap[String(di + 1)]?.[p.period] ?? 0,
                          allFree =
                            analysis.users.length > 0 &&
                            free === analysis.users.length
                        return (
                          <td key={d}>
                            <button
                              className={`routine-heat-cell ${allFree ? 'all-free' : free ? 'some-free' : ''}${selectedSlot?.day === di + 1 && selectedSlot.period === p.period ? ' selected' : ''}`}
                              aria-label={`${d}${p.label}，${free} 人无课`}
                              onClick={() =>
                                setSelectedSlot({
                                  day: di + 1,
                                  period: p.period,
                                  label: p.label,
                                })
                              }
                            >
                              <strong>{free}</strong>
                              {allFree ? '全员无课' : '人无课'}
                            </button>
                          </td>
                        )
                      })}
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        ) : (
          <div className="routine-empty">
            <Empty description="选择人员与学期后分析共同空闲" />
          </div>
        )}
      </GlassCard>
      <aside className="routine-side">
        <GlassCard className="routine-pad routine-today">
          <h3>所选时段</h3>
          {analysis && selectedSlot ? (
            <>
              <strong>
                {WEEK_DAYS[selectedSlot.day - 1]} · {selectedSlot.label}
              </strong>
              <p>
                {analysis.heatmap[String(selectedSlot.day)]?.[
                  selectedSlot.period
                ] ?? 0}{' '}
                / {analysis.users.length} 人无课
              </p>
              <h4>参与分析成员</h4>
              <p>
                {analysis.users.map((u) => u.realName).join('、') ||
                  '没有参与成员'}
              </p>
              <RoutineNote>
                人数来自已保存课表；课程变化后请更新课表并重新分析。
              </RoutineNote>
            </>
          ) : (
            <p>分析后点击热力图中的时段，查看共同空闲人数。</p>
          )}
        </GlassCard>
        {analysis && (
          <GlassCard className="routine-pad">
            <h3>人数最多的时段</h3>
            {analysis.optimal.slice(0, 5).map((s) => (
              <div
                className="routine-side-link"
                key={`${s.dayOfWeek}-${s.period}`}
              >
                <Button
                  type="link"
                  onClick={() =>
                    setSelectedSlot({
                      day: s.dayOfWeek,
                      period: s.period,
                      label: s.label,
                    })
                  }
                >
                  {WEEK_DAYS[s.dayOfWeek - 1]} {s.label}
                </Button>
                <small>
                  {s.freeCount}/{analysis.users.length} 人无课
                </small>
              </div>
            ))}
          </GlassCard>
        )}
      </aside>
    </div>
  )
  const importPanel = (
    <>
      <div className="routine-workspace">
        <GlassCard>
          <div className="routine-process">
            <span className={!result ? 'current' : ''}>
              <i>1</i>选择部门与文件
            </span>
            <hr />
            <span className={result ? 'current' : ''}>
              <i>2</i>核对生成结果
            </span>
            <hr />
            <span>
              <i>3</i>用于排班
            </span>
          </div>
          <div className="routine-toolbar">
            <label className="routine-filter">
              目标部门
              <Select
                aria-label="导入目标部门"
                disabled={importing}
                value={impDeptId}
                onChange={(id) => {
                  setImpDeptId(id)
                  setResult(null)
                }}
                placeholder="选择部门"
                options={depts.map((d) => ({ value: d.id, label: d.name }))}
              />
            </label>
            <label className="routine-filter">
              学期
              <Select
                aria-label="导入学期"
                disabled={importing}
                value={impSemester}
                onChange={(s) => {
                  setImpSemester(s)
                  setResult(null)
                }}
                options={allSemesters.map((s) => ({ value: s, label: s }))}
              />
            </label>
            <span className="routine-muted">按文件名或表内姓名识别成员</span>
          </div>
          <div className="routine-import-dropzone">
            <Upload.Dragger
              multiple
              accept=".xlsx,.xls"
              disabled={importing}
              fileList={files}
              showUploadList={false}
              beforeUpload={(file) => {
                if (!/\.(xls|xlsx)$/i.test(file.name)) {
                  message.warning('请选择 .xls 或 .xlsx 文件')
                  return Upload.LIST_IGNORE
                }
                return false
              }}
              onChange={({ fileList }) => {
                setFiles(fileList)
                setResult(null)
              }}
            >
              <div className="ant-upload-drag-icon">
                <InboxOutlined />
              </div>
              <h3>批量导入成员课程表</h3>
              <p>点击选择或拖入文件，支持 .xls、.xlsx，可一次选择多个文件</p>
              <Button icon={<PlusIcon />}>选择课表文件</Button>
            </Upload.Dragger>
          </div>
          <div className="routine-import-files">
            {files.map((f) => (
              <div className="routine-import-file" key={f.uid}>
                <div>
                  {f.name}
                  <small>
                    {Math.max(1, Math.round((f.size ?? 0) / 1024))} KB
                    {result?.failed.find((x) => x.fileName === f.name)
                      ?.reason &&
                      ` / ${result.failed.find((x) => x.fileName === f.name)!.reason}`}
                  </small>
                </div>
                {result && (
                  <span
                    className={`tone-tag ${result.failed.some((x) => x.fileName === f.name) ? 'tone-danger' : 'tone-success'}`}
                  >
                    {result.failed.some((x) => x.fileName === f.name)
                      ? '导入失败'
                      : '已解析'}
                  </span>
                )}
                <Button
                  type="text"
                  danger
                  disabled={importing}
                  icon={<DeleteOutlined />}
                  aria-label={`移除 ${f.name}`}
                  onClick={() => {
                    setFiles((fs) => fs.filter((x) => x.uid !== f.uid))
                    setResult(null)
                  }}
                />
              </div>
            ))}
          </div>
          <div className="routine-table-foot">
            <span>已选择 {files.length} 份课表</span>
            <Button
              type="primary"
              disabled={!files.length || !impDeptId}
              loading={importing}
              onClick={() => setConfirmImport(true)}
            >
              生成无课表
            </Button>
          </div>
        </GlassCard>
        <aside className="routine-side">
          <GlassCard className="routine-pad">
            <h3>导入前检查</h3>
            <div className="routine-side-link">
              <strong>文件包含姓名与课程信息</strong>
              <small>无法识别姓名时，修改文件名后重传</small>
            </div>
            <div className="routine-side-link">
              <strong>周次、单双周与分段课程</strong>
              <small>结果显示每位成员的无课周次</small>
            </div>
            <div className="routine-side-link">
              <strong>相同部门和学期重新生成</strong>
              <small>覆盖最新无课表，请先核对目标部门</small>
            </div>
            <RoutineNote>
              成功文件会生成最新结果；失败文件需要修正后重新导入。
            </RoutineNote>
          </GlassCard>
        </aside>
      </div>
      {result && (
        <GlassCard className="routine-import-results">
          <div className="business-section-head">
            <div>
              <h3>生成结果 · {result.deptName}</h3>
              <p className="routine-muted">
                {result.successCount} 份成功 / {result.failed.length} 份失败 /{' '}
                {result.semester}
              </p>
            </div>
            <Space wrap>
              <Button
                icon={<DownloadOutlined />}
                onClick={downloadXlsx}
                disabled={!result.downloadUrl}
              >
                下载 Excel
              </Button>
              <Button icon={<CopyOutlined />} onClick={copyMarkdown}>
                复制 Markdown
              </Button>
              <Button
                type="primary"
                onClick={() =>
                  navigate(
                    `/routine/free-schedules?deptId=${impDeptId}&semester=${encodeURIComponent(result.semester)}`,
                  )
                }
              >
                查看无课表
              </Button>
            </Space>
          </div>
          {(result.failed.length > 0 || result.warnings.length > 0) && (
            <div className="routine-pad">
              {result.failed.length > 0 && (
                <RoutineNote warning>
                  {result.failed
                    .map((f) => `${f.fileName}：${f.reason}`)
                    .join('；')}
                </RoutineNote>
              )}
              {result.warnings.map((w) => (
                <RoutineNote key={w} warning>
                  {w}
                </RoutineNote>
              ))}
            </div>
          )}
          <FreeScheduleGrid rows={result.rows} />
        </GlassCard>
      )}
    </>
  )
  return (
    <RoutineLayout>
      <PageHeader
        title="无课表制作"
        description="维护个人课表，查找共同空闲，或批量制作部门无课表。"
      />
      <Tabs
        className="routine-tabs"
        activeKey={canManage || tab !== 'import' ? tab : 'edit'}
        onChange={setTabWithUrl}
        items={[
          { key: 'edit', label: '我的课程表', children: editPanel },
          { key: 'analyze', label: '共同空闲分析', children: heatmapPanel },
          ...(canManage
            ? [{ key: 'import', label: '批量导入', children: importPanel }]
            : []),
        ]}
      />
      <FormSurface
        mode="dialog"
        title={
          cell && Object.hasOwn(matrix, `${cell.day}-${cell.period}`)
            ? '编辑课程时段'
            : '添加课程时段'
        }
        open={!!cell}
        destroyOnHidden
        onCancel={() => setCell(null)}
        onOk={() => applyCell(cellName)}
        footer={
          <Space>
            {cell && Object.hasOwn(matrix, `${cell.day}-${cell.period}`) && (
              <Button onClick={() => applyCell(undefined, true)}>
                标记无课
              </Button>
            )}
            <Button onClick={() => setCell(null)}>取消</Button>
            <Button type="primary" onClick={() => applyCell(cellName)}>
              保存时段
            </Button>
          </Space>
        }
      >
        <p>{cell && `${WEEK_DAYS[cell.day - 1]} · ${cell.label}`}</p>
        <label className="routine-filter">
          课程名称（可留空）
          <Input
            aria-label="课程名称（可留空）"
            value={cellName}
            onChange={(e) => setCellName(e.target.value)}
            maxLength={60}
            placeholder="填写课程名称"
            onPressEnter={() => applyCell(cellName)}
          />
        </label>
        <RoutineNote>
          时段修改后，点击“保存课程表”使共同空闲分析生效。
        </RoutineNote>
      </FormSurface>
      <FormSurface
        mode="dialog"
        title="切换学期"
        open={!!pendingSemester}
        onCancel={() => setPendingSemester(null)}
        okText="放弃修改并切换"
        onOk={() => {
          if (pendingSemester) setSemester(pendingSemester)
          setPendingSemester(null)
          setDirty(false)
          setAnalysis(null)
        }}
      >
        <p>当前课程表有未保存修改，切换学期会丢弃这些修改。</p>
      </FormSurface>
      <FormSurface
        mode="dialog"
        title="确认生成部门无课表"
        open={confirmImport}
        onCancel={() => !importing && setConfirmImport(false)}
        confirmLoading={importing}
        okText="确认生成"
        onOk={runImport}
      >
        <h3>
          {depts.find((d) => d.id === impDeptId)?.name} / {impSemester}
        </h3>
        <p>本次导入 {files.length} 份课表文件。</p>
        <RoutineNote warning>
          同部门、同学期的最新无课结果会被替换，请核对目标部门与文件。
        </RoutineNote>
      </FormSurface>
    </RoutineLayout>
  )
}

function PlusIcon() {
  return <UploadOutlined />
}
