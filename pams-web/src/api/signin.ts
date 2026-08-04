import { get, post, put, del, http } from './http'

export interface SigninVO {
  id: number
  activityId: number
  personId: number | null
  name: string
  studentNo: string | null
  className: string | null
  identityType: string | null
  signType: 'MANUAL' | 'SCAN' | null
  signTime: string | null
  location: string | null
  phone: string | null
  remark: string | null
  createdAt: string
}

export interface SigninSave {
  activityId: number
  personId?: number | null
  name: string
  studentNo?: string | null
  className?: string | null
  identityType?: string | null
  signType?: 'MANUAL' | 'SCAN'
  signTime?: string | null
  location?: string | null
  phone?: string | null
  remark?: string | null
}

export interface SigninToken {
  token: string
  activityId: number
  expiresAt: string
  /** 扫码 URL：{origin}/signin/{token}，由后端依据请求 origin 拼好 */
  qrContent: string
}

export const listSignins = (activityId: number, keyword?: string) =>
  get<SigninVO[]>('/signins', { activityId, keyword })
export const createSignin = (data: SigninSave) => post<SigninVO>('/signins', data)
export const deleteSignin = (id: number) => del<void>(`/signins/${id}`)
export const countSignins = (activityId: number) =>
  get<number>(`/signins/${activityId}/count`)
export const generateSigninToken = (activityId: number) =>
  post<SigninToken>('/signins/token', { activityId })
/** 扫码签到（免登录）：无效/过期 token 由后端返回业务错误，http 拦截层统一提示 */
export const scanSignin = (data: { token: string; name: string; studentNo?: string }) =>
  post<SigninVO>('/signins/scan', data)

// ==================== 应签名单 ====================

export interface SigninRosterVO {
  id: number
  activityId: number
  fields: Record<string, string>
  signed: boolean
}

export interface SigninFieldConfigVO {
  id: number
  activityId: number
  fieldName: string
  fieldKey: string
  /** 后端实体存 Integer（0/1），返回 JSON 为 number；统一归一为 boolean 便于前端消费 */
  required: boolean
  fieldType: string
  sortOrder: number
}

export interface SigninSummaryVO {
  expected: number
  signed: number
  unsigned: number
}

export interface SigninRosterUploadResult {
  added: number
  skipped: number
}

export type RosterStatus = 'ALL' | 'SIGNED' | 'UNSIGNED'

/** 应签名单列表（status：ALL/SIGNED/UNSIGNED，缺省 ALL） */
export const listRoster = (activityId: number, status?: RosterStatus) =>
  get<SigninRosterVO[]>('/signins/roster', { activityId, status })

/** 上传 Excel 应签名单（multipart：activityId + file）。用原生 http.post，axios 自动设 multipart 头 */
export const uploadRoster = (activityId: number, file: File) => {
  const form = new FormData()
  form.append('activityId', String(activityId))
  form.append('file', file)
  return http.post('/signins/roster/upload', form) as unknown as Promise<SigninRosterUploadResult>
}

/** 删除名单行 */
export const deleteRoster = (id: number) => del<void>(`/signins/roster/${id}`)

/** 应签汇总：expected/signed/unsigned */
export const rosterSummary = (activityId: number) =>
  get<SigninSummaryVO>('/signins/roster/summary', { activityId })

/** 核验字段配置列表（后端实体 required 为 Integer 0/1，此处归一为 boolean） */
export const getSigninFields = async (activityId: number): Promise<SigninFieldConfigVO[]> => {
  const rows = await get<Array<SigninFieldConfigVO & { required?: boolean | number }>>('/signins/fields', { activityId })
  return (rows ?? []).map((r) => ({ ...r, required: !!r.required }))
}

/** 保存核验字段配置。注意后端签名为 PUT /signins/fields?activityId= + body 为字段数组（见 SigninRosterController.saveFields） */
export const saveSigninFields = (
  activityId: number,
  fields: Array<{ fieldName: string; fieldKey: string; required: boolean; fieldType: string }>,
) => put<void>('/signins/fields', fields, { params: { activityId } })

/** 手动补签（幂等）：对未签名单行补建签到记录，返回实际补签条数 */
export const backfillSignins = (activityId: number, rosterIds: number[]) =>
  post<number>('/signins/backfill', { activityId, rosterIds })
