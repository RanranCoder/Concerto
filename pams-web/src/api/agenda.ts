import { get, post, put, del } from './http'
import type { ActivityAgendaVO } from './activity'

export interface AgendaSave {
  activityId: number
  stepNo: number
  title: string
  remark?: string | null
}

export const listAgendas = (activityId: number) =>
  get<ActivityAgendaVO[]>('/agendas', { activityId })
export const createAgenda = (data: AgendaSave) => post<ActivityAgendaVO>('/agendas', data)
export const updateAgenda = (id: number, data: AgendaSave) => put<void>(`/agendas/${id}`, data)
export const deleteAgenda = (id: number) => del<void>(`/agendas/${id}`)
