import axios, { AxiosError } from 'axios'
import type { AxiosRequestConfig } from 'axios'
import { useAuthStore } from '@/stores/auth'
import { message } from '@/utils/feedback'

export class ApiError extends Error {
  code: number
  constructor(code: number, msg: string) {
    super(msg)
    this.code = code
  }
}

export const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.request.use((config) => {
  const token = useAuthStore.getState().token
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  // 返回类型故意标注为 any：拦截器把 Result 解包为 body.data（非 AxiosResponse），
  // 由下方辅助函数以 `as unknown as Promise<T>` 断言回类型。
  (res): any => {
    // blob（文件下载）响应跳过 Result 解包，原样返回 AxiosResponse 供导出下载使用
    if (res.config.responseType === 'blob') return res
    const body = res.data as { code: number; message?: string; data?: unknown }
    if (body.code === 200) return body.data
    throw new ApiError(body.code ?? -1, body.message ?? '请求失败')
  },
  (err: AxiosError<{ message?: string }>) => {
    if (err.response?.status === 401) {
      useAuthStore.getState().logout()
      window.location.href = '/login'
    }
    message.error(err.response?.data?.message ?? err.message)
    return Promise.reject(err)
  },
)

export function get<T>(url: string, params?: object) { return http.get(url, { params }) as unknown as Promise<T> }
export function post<T>(url: string, data?: object, config?: AxiosRequestConfig) {
  return http.post(url, data, config) as unknown as Promise<T>
}
export function put<T>(url: string, data?: object, config?: AxiosRequestConfig) {
  return http.put(url, data, config) as unknown as Promise<T>
}
export function del<T>(url: string, config?: AxiosRequestConfig) {
  return http.delete(url, config) as unknown as Promise<T>
}
