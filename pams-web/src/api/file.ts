import { http } from './http'

/** 文件记录（后端 FileRecord 实体） */
export interface FileRec {
  id: number
  filename: string
  storedName?: string
  path: string
  size: number | null
  contentType: string | null
  bizType: string | null
  uploaderId: number | null
  createdAt: string
}

/**
 * multipart 上传，返回后端 FileRecord（含新生成的 fileId）。
 * onProgress 可选：上传字节百分比（0~100），用于进度条。
 */
export const uploadFile = (file: File, bizType?: string, onProgress?: (percent: number) => void) => {
  const form = new FormData()
  form.append('file', file)
  if (bizType) form.append('bizType', bizType)
  return http
    .post('/files/upload', form, {
      onUploadProgress: (e) => {
        if (e.total) onProgress?.(Math.round((e.loaded / e.total) * 100))
      },
    })
    .then((r) => r as unknown as FileRec)
}

/** 下载地址（公开 URL，供新窗口打开等场景；需认证时请用 downloadFile） */
export const downloadUrl = (id: number) => `/api/files/${id}/download`

/**
 * 走 axios + JWT 下载：window.open 新窗口不携带 Authorization 头会 401，
 * 这里用 responseType blob 拉取后触发浏览器保存。文件名优先取 Content-Disposition。
 */
export const downloadFile = async (id: number, fallbackName = '下载文件') => {
  const res = (await http.get(`/files/${id}/download`, { responseType: 'blob' })) as unknown as {
    data: Blob
    headers: Record<string, string>
  }
  let filename = fallbackName
  try {
    const cd = res.headers?.['content-disposition']
    if (cd) {
      const m = /filename\*=UTF-8''([^;]+)/i.exec(cd) || /filename="?([^";]+)"?/i.exec(cd)
      if (m?.[1]) filename = decodeURIComponent(m[1])
    }
  } catch {
    /* 保持 fallbackName */
  }
  const url = URL.createObjectURL(res.data)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  URL.revokeObjectURL(url)
}
