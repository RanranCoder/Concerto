import { get, post, put, del } from './http'
import type { SeatMapVO } from './activity'

export interface SeatSave {
  activityId: number
  roomName?: string | null
  zone: string
  rowNo?: number | null
  colNo?: number | null
  personName?: string | null
  seatType?: string | null
}

/** 座位表：按 zone 分组返回 {"zone": [座位...]} */
export const listSeats = (activityId: number) =>
  get<Record<string, SeatMapVO[]>>('/seats', { activityId })
export const createSeat = (data: SeatSave) => post<SeatMapVO>('/seats', data)
export const updateSeat = (id: number, data: SeatSave) => put<void>(`/seats/${id}`, data)
export const deleteSeat = (id: number) => del<void>(`/seats/${id}`)
