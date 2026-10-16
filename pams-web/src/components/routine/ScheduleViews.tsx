import { Button, Drawer, Empty, Popconfirm, Space, Table } from 'antd'
import { LeftOutlined, RightOutlined } from '@ant-design/icons'
import type { ColumnsType } from 'antd/es/table'
import type { ReactNode } from 'react'
import { useNavigate } from 'react-router-dom'
import dayjs, { type Dayjs } from 'dayjs'
import type { ScheduleVO } from '@/api/schedule'
import { SCHEDULE_TYPE_MAP } from '@/api/schedule'
import type { ActivityVO } from '@/api/activity'
import type { ScheduleConfigVO } from '@/api/courseSchedule'
import { periodFor, weekday, WEEK_DAYS, timeRange } from '@/utils/routine'
import { RoutineNote, RoutineStatus } from './RoutineLayout'

export function shiftTitle(s: ScheduleVO, activities: ActivityVO[] = []) {
  return s.scheduleType === 'ACTIVITY'
    ? activities.find((a) => a.id === s.activityId)?.name || '活动排班'
    : SCHEDULE_TYPE_MAP[s.scheduleType] || s.scheduleType
}
export function ShiftPeople({ shift }: { shift: ScheduleVO }) {
  return (
    <span className="routine-people">
      {(shift.persons ?? []).map((p) => (
        <span
          className="routine-person"
          key={p.id ?? `${p.userId}-${p.personName}`}
        >
          <span className="routine-avatar">{p.personName.slice(0, 1)}</span>
          {p.personName}
          {p.isPrimary === 1 && <span className="routine-primary">主班</span>}
        </span>
      ))}
    </span>
  )
}
export function WeekControls({
  start,
  onChange,
  extra,
}: {
  start: Dayjs
  onChange: (d: Dayjs) => void
  extra?: ReactNode
}) {
  const today = dayjs(),
    thisMonday = today.subtract((today.day() + 6) % 7, 'day').startOf('day')
  return (
    <div className="business-section-head">
      <div>
        <h3>
          {start.isSame(thisMonday, 'day') ? '本周排班' : '周排班'}{' '}
          <small>
            {start.format('MM-DD')} 至 {start.add(6, 'day').format('MM-DD')}
          </small>
        </h3>
      </div>
      <Space wrap>
        <Button
          icon={<LeftOutlined />}
          aria-label="上一周"
          onClick={() => onChange(start.subtract(7, 'day'))}
        />
        <Button onClick={() => onChange(thisMonday)}>本周</Button>
        <Button
          icon={<RightOutlined />}
          aria-label="下一周"
          onClick={() => onChange(start.add(7, 'day'))}
        />
        {extra}
      </Space>
    </div>
  )
}
export function ScheduleGrid({
  rows,
  periods,
  start,
  activities,
  onOpen,
  onCreate,
  sevenDays = false,
}: {
  rows: ScheduleVO[]
  periods: ScheduleConfigVO[]
  start: Dayjs
  activities: ActivityVO[]
  onOpen: (s: ScheduleVO) => void
  onCreate?: (day: number, p: ScheduleConfigVO) => void
  sevenDays?: boolean
}) {
  return (
    <div className="routine-table-scroll">
      <table className="routine-week-table">
        <thead>
          <tr>
            <th scope="col">节次 / 时间</th>
            {WEEK_DAYS.slice(0, sevenDays ? 7 : 5).map((d, i) => (
              <th
                scope="col"
                key={d}
                className={
                  start.add(i, 'day').isSame(dayjs(), 'day') ? 'today' : ''
                }
              >
                {d}
                {start.add(i, 'day').isSame(dayjs(), 'day') && ' · 今天'}
                <b>{start.add(i, 'day').format('MM / DD')}</b>
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {periods.map((p, pi) => (
            <tr key={p.period}>
              <th scope="row">
                {p.label}
                <small>
                  {p.startTime
                    ? `${p.startTime.slice(0, 5)}–${p.endTime?.slice(0, 5) ?? ''}`
                    : '节次时间未配置'}
                </small>
              </th>
              {WEEK_DAYS.slice(0, sevenDays ? 7 : 5).map((d, di) => {
                const slots = rows.filter(
                  (s) => weekday(s) === di + 1 && periodFor(s, periods) === pi,
                )
                return (
                  <td
                    key={d}
                    className={
                      start.add(di, 'day').isSame(dayjs(), 'day') ? 'today' : ''
                    }
                  >
                    {slots.map((s) => (
                      <button
                        type="button"
                        className={`routine-shift ${s.scheduleType === 'ACTIVITY' ? 'activity' : ['ARCHIVE', 'STAMP'].includes(s.scheduleType) ? 'document' : ''}`}
                        key={s.id}
                        onClick={() => onOpen(s)}
                      >
                        <strong>{shiftTitle(s, activities)}</strong>
                        <small>{s.location || '地点未设置'}</small>
                        <span>
                          {s.persons?.map((person, index) => (
                            <span key={person.id ?? index}>
                              {index > 0 && ' / '}
                              {person.personName}
                              {person.isPrimary === 1 && (
                                <i className="routine-primary">主</i>
                              )}
                            </span>
                          ))}
                        </span>
                        {s.scheduleType === 'ACTIVITY' && (
                          <small>
                            <RoutineStatus status={s.status} />
                          </small>
                        )}
                      </button>
                    ))}
                    {!slots.length &&
                      (onCreate ? (
                        <button
                          type="button"
                          className="routine-add-cell"
                          onClick={() => onCreate(di + 1, p)}
                        >
                          ＋ 添加排班
                        </button>
                      ) : (
                        <span className="routine-muted">—</span>
                      ))}
                  </td>
                )
              })}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  )
}
export function ScheduleTable({
  rows,
  activities,
  onOpen,
  onEdit,
  loading = false,
}: {
  rows: ScheduleVO[]
  activities: ActivityVO[]
  onOpen: (s: ScheduleVO) => void
  onEdit?: (s: ScheduleVO) => void
  loading?: boolean
}) {
  const columns: ColumnsType<ScheduleVO> = [
    {
      title: '工作安排',
      key: 'title',
      render: (_, s) => (
        <>
          <strong>{shiftTitle(s, activities)}</strong>
          <small>
            {s.scheduleType === 'ACTIVITY' ? (
              <RoutineStatus status={s.status} />
            ) : (
              '例行事务'
            )}
          </small>
        </>
      ),
    },
    {
      title: '日期 / 时间',
      key: 'date',
      render: (_, s) => (
        <>
          {s.scheduleDate ||
            (s.weekNo
              ? `第 ${s.weekNo} 周 ${WEEK_DAYS[(s.weekday ?? 1) - 1]}`
              : '日期未设置')}
          <small>{timeRange(s)}</small>
        </>
      ),
    },
    {
      title: '值班人员',
      key: 'people',
      render: (_, s) => <ShiftPeople shift={s} />,
    },
    { title: '地点', dataIndex: 'location', render: (v) => v || '未设置' },
    {
      title: '操作',
      key: 'actions',
      width: 130,
      render: (_, s) => (
        <Space>
          <Button type="link" onClick={() => onOpen(s)}>
            查看
          </Button>
          {onEdit && (
            <Button type="link" onClick={() => onEdit(s)}>
              编辑
            </Button>
          )}
        </Space>
      ),
    },
  ]
  return (
    <Table
      className="routine-data-table"
      rowKey="id"
      columns={columns}
      dataSource={rows}
      loading={loading}
      scroll={{ x: 850 }}
      pagination={{
        pageSize: 15,
        showSizeChanger: true,
        showTotal: (n) => `共 ${n} 个班次`,
      }}
      locale={{ emptyText: <Empty description="当前筛选下暂无排班" /> }}
    />
  )
}
export function ScheduleDetail({
  shift,
  activities,
  onClose,
  onEdit,
  onDelete,
  extra,
}: {
  shift: ScheduleVO | null
  activities: ActivityVO[]
  onClose: () => void
  onEdit?: (s: ScheduleVO) => void
  onDelete?: (s: ScheduleVO) => Promise<void>
  extra?: ReactNode
}) {
  const navigate = useNavigate()
  return (
    <Drawer
      title="班次详情"
      open={!!shift}
      width="min(540px, 100vw)"
      onClose={onClose}
      destroyOnHidden
      className="routine-drawer"
      footer={
        <Space>
          <Button onClick={onClose}>关闭</Button>
          {shift && onEdit && (
            <Button type="primary" onClick={() => onEdit(shift)}>
              编辑班次
            </Button>
          )}
        </Space>
      }
    >
      {shift && (
        <>
          <div className="routine-detail-heading">
            <h3>{shiftTitle(shift, activities)}</h3>
            {shift.scheduleType === 'ACTIVITY' && (
              <RoutineStatus status={shift.status} />
            )}
            <p>
              {shift.scheduleDate || `第 ${shift.weekNo ?? '未设置'} 周`} /{' '}
              {timeRange(shift)}
            </p>
          </div>
          <dl className="routine-detail-kv">
            <dt>值班地点</dt>
            <dd>{shift.location || '未设置'}</dd>
            <dt>值班人员</dt>
            <dd>
              <ShiftPeople shift={shift} />
            </dd>
            <dt>班次来源</dt>
            <dd>{shift.source === 'AUTO' ? '无课表生成' : '人工排班'}</dd>
            <dt>工作备注</dt>
            <dd>{shift.notes || '无'}</dd>
          </dl>
          {shift.scheduleType === 'ACTIVITY' && shift.activityId && (
            <Space wrap>
              <Button
                type="link"
                onClick={() => navigate(`/activities/${shift.activityId}`)}
              >
                查看关联活动
              </Button>
              <Button
                type="link"
                onClick={() =>
                  navigate(`/routine/attendance?activityId=${shift.activityId}`)
                }
              >
                查看活动考勤
              </Button>
            </Space>
          )}
          <RoutineNote>
            {shift.status === 'DRAFT'
              ? '草稿尚未进入考勤，复核后发布。'
              : '主班负责本班事务，副班协助完成。请及时核对人员与时间。'}
          </RoutineNote>
          <div className="routine-detail-actions">
            {extra}
            {onDelete && (
              <Popconfirm
                title="删除这个班次？"
                description="相关考勤记录也会删除。"
                okText="删除"
                cancelText="返回"
                onConfirm={() => onDelete(shift)}
              >
                <Button danger>删除班次</Button>
              </Popconfirm>
            )}
          </div>
        </>
      )}
    </Drawer>
  )
}
