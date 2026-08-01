import { get } from './http'

export interface DeptVO {
  id: number
  name: string
  sortOrder: number | null
}

export const listDepts = () => get<DeptVO[]>('/depts')
