import { get, post, del } from './http'

/** 考勤状态：PRESENT 出勤 / LEAVE 请假 / ABSENT 缺勤 */
export const ATTENDANCE_STATUS = ['PRESENT', 'LEAVE', 'ABSENT'] as const

export const ATTENDANCE_STATUS_MAP: Record<string, string> = {
  PRESENT: '出勤',
  LEAVE: '请假',
  ABSENT: '缺勤',
}

export const ATTENDANCE_STATUS_OPTIONS = ATTENDANCE_STATUS.map((v) => ({ value: v, label: ATTENDANCE_STATUS_MAP[v] }))

export interface AttendanceVO {
  id: number
  scheduleId: number
  personId: number | null
  personName: string
  /** PRESENT / LEAVE / ABSENT */
  status: string
  remark: string | null
  recordTime: string
  createdAt: string
}

export interface AttendanceSave {
  scheduleId: number
  personId?: number | null
  personName: string
  status: string
  remark?: string | null
}

/** 按人汇总行：应到 shouldAttend / 实到 present / 请假 leave / 缺勤 absent / 次数 count */
export interface AttendanceSummaryVO {
  personName: string
  shouldAttend: number
  present: number
  leave: number
  absent: number
  count: number
}

export const listAttendances = (params: { scheduleId?: number; weekNo?: number; personName?: string }) =>
  get<AttendanceVO[]>('/attendances', params)
export const createAttendance = (data: AttendanceSave) => post<AttendanceVO>('/attendances', data)
export const deleteAttendance = (id: number) => del<void>(`/attendances/${id}`)
/**
 * 按人汇总。weekNo / type 均为可选；weekNo 按考勤所属排班的周次匹配，
 * type 按考勤所属排班的排班类型（scheduleType，如 SMOKING_CURB）匹配；
 * 未关联排班的考勤不匹配任何 weekNo/type。
 */
export const summaryAttendance = (params: { weekNo?: number; type?: string }) =>
  get<AttendanceSummaryVO[]>('/attendances/summary', params)
