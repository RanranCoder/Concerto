import { get, post, put, del } from './http'

/** 考勤状态：待签到/正常/迟到/缺勤/请假/早退/补签/取消 */
export const ATTENDANCE_STATUS = [
  'PENDING',
  'NORMAL',
  'LATE',
  'ABSENT',
  'LEAVE',
  'EARLY_LEAVE',
  'MAKEUP',
  'CANCELED',
] as const

export const ATTENDANCE_STATUS_MAP: Record<string, string> = {
  PENDING: '待签到',
  NORMAL: '正常',
  LATE: '迟到',
  ABSENT: '缺勤',
  LEAVE: '请假',
  EARLY_LEAVE: '早退',
  MAKEUP: '补签',
  CANCELED: '取消',
}

/** 用于人工修改状态的可选状态集合（去掉待签到，允许正常/迟到等） */
export const ATTENDANCE_MANUAL_OPTIONS = ATTENDANCE_STATUS.filter(
  (s) => s !== 'PENDING' && s !== 'CANCELED',
).map((v) => ({ value: v, label: ATTENDANCE_STATUS_MAP[v] }))

export const ATTENDANCE_STATUS_OPTIONS = ATTENDANCE_STATUS.map((v) => ({
  value: v,
  label: ATTENDANCE_STATUS_MAP[v],
}))

export interface AttendanceVO {
  id: number
  scheduleId: number | null
  sessionId: number | null
  personId: number | null
  personName: string
  studentNo: string | null
  status: string
  statusSource: 'AUTO' | 'MANUAL' | null
  remark: string | null
  checkedInAt: string | null
  recordTime: string | null
  activityId: number | null
  activityName: string | null
  activityType: string | null
  activityDate: string | null
  activityStart: string | null
  activityEnd: string | null
  sessionName: string | null
  signCount: number
}

export interface AttendanceSave {
  scheduleId: number
  personId?: number | null
  personName: string
  status: string
  remark?: string | null
}

export interface AttendanceStats {
  totalActivities: number
  shouldAttend: number
  signed: number
  normal: number
  late: number
  absent: number
  leave: number
  makeup: number
  pending: number
}

export interface AttendanceAuditVO {
  id: number
  attendanceId: number
  oldStatus: string | null
  newStatus: string | null
  operatorId: number | null
  operatorName: string | null
  operateTime: string | null
  remark: string | null
}

export interface AttendanceFilter {
  activityId?: number
  date?: string
  dateFrom?: string
  dateTo?: string
  type?: string
  keyword?: string
  status?: string
}

export const listAttendances = (params: AttendanceFilter) => get<AttendanceVO[]>('/attendances', params)
export const getAttendanceStats = (params: AttendanceFilter) => get<AttendanceStats>('/attendances/stats', params)
export const listMyAttendances = () => get<AttendanceVO[]>('/attendances/my')
export const updateAttendanceStatus = (id: number, data: { newStatus: string; remark?: string }) =>
  put<AttendanceVO>(`/attendances/${id}/status`, data)
export const makeupAttendance = (id: number, remark?: string) =>
  post<AttendanceVO>(`/attendances/${id}/makeup`, undefined, { params: { remark } })
export const listAttendanceAudits = (id: number) => get<AttendanceAuditVO[]>(`/attendances/${id}/audits`)

export const createAttendance = (data: AttendanceSave) => post<AttendanceVO>('/attendances', data)
export const deleteAttendance = (id: number) => del<void>(`/attendances/${id}`)
/** 按人汇总（兼容保留） */
export const summaryAttendance = (params: { weekNo?: number; type?: string }) =>
  get<Array<{ personName: string; shouldAttend: number; present: number; leave: number; absent: number; count: number }>>(
    '/attendances/summary',
    params,
  )