import { get } from './http'

/** 系统信息（设置页展示，GET /api/system/info） */
export interface SystemInfoVO {
  version: string
  uploadDir: string
  ping: string
}

export const getSystemInfo = () => get<SystemInfoVO>('/system/info')
