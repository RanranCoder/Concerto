import { http } from '@/api/http'

/** 拉取文件二进制（带 JWT），供 docx/xlsx 前端解析预览使用 */
export async function fetchFileBlob(fileId: number, signal?: AbortSignal): Promise<Blob> {
  const res = (await http.get(`/files/${fileId}/download`, { responseType: 'blob', signal, silentError: !!signal })) as unknown as {
    data: Blob
  }
  return res.data
}
