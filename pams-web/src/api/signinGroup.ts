import { get, put, del, http } from './http'

// ==================== F02 签到分组 ====================

export interface GroupPerson {
  id: number
  groupId: number
  fields: Record<string, string>
  signed: boolean
}

export interface SignInGroupVO {
  id: number
  activityId: number
  groupName: string
  sourceFilename: string
  sortOrder: number
  count: number
  signedCount: number
  unsignedCount: number
  createdAt: string
  people: GroupPerson[]
}

export interface SignInGroupSummary {
  total: number
  signed: number
  unsigned: number
  groupCount: number
}

export interface GroupUploadResult {
  groupId: number
  groupName: string
  added: number
  skipped: number
}

export const listSignInGroups = (activityId: number, keyword?: string) =>
  get<SignInGroupVO[]>('/signins/groups', { activityId, keyword })
export const signInGroupSummary = (activityId: number) =>
  get<SignInGroupSummary>('/signins/groups/summary', { activityId })
export const uploadSignInGroup = (activityId: number, file: File, groupId?: number) => {
  const form = new FormData()
  form.append('activityId', String(activityId))
  if (groupId) form.append('groupId', String(groupId))
  form.append('file', file)
  return http.post('/signins/groups/upload', form) as unknown as Promise<GroupUploadResult>
}
export const renameSignInGroup = (id: number, groupName: string) =>
  put<void>(`/signins/groups/${id}/rename`, { groupName })
export const sortSignInGroups = (ids: number[]) => put<void>('/signins/groups/sort', { ids })
export const deleteSignInGroup = (id: number) => del<void>(`/signins/groups/${id}`)
export const deleteSignInGroups = (ids: number[]) => del<number>('/signins/groups/batch', { data: { ids } })
export const deleteSignInPerson = (rosterId: number) => del<void>(`/signins/groups/persons/${rosterId}`)
export const deleteSignInPersons = (ids: number[]) => del<number>('/signins/groups/persons/batch', { data: { ids } })
