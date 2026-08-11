import { useRef } from 'react'
import { App, Upload } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import { uploadFile, downloadUrl } from '@/api/file'

/**
 * 长图上传：手动上传拿 FileRec，回填 /api/files/{id}/download；受控 Form.Item value/onChange。
 * - uid 用 url 保证跨渲染稳定（避免 antd 反复重挂载导致缩略图闪烁）
 * - valueRef 同步最新值，避免并发多选上传时闭包读到过期 value 而丢 URL
 */
export default function LongImageUpload({
  value,
  onChange,
  max = 9,
}: {
  value?: string[]
  onChange?: (urls: string[]) => void
  max?: number
}) {
  const { message } = App.useApp()
  const valueRef = useRef(value)
  valueRef.current = value
  const list = (value ?? []).map((url, i) => ({
    uid: url,
    name: `长图${i + 1}`,
    status: 'done' as const,
    url,
  }))
  return (
    <Upload
      listType="picture-card"
      accept="image/*"
      fileList={list}
      beforeUpload={(file) => {
        uploadFile(file as unknown as File, 'article')
          .then((rec) => {
            const next = [...(valueRef.current ?? []), downloadUrl(rec.id)]
            valueRef.current = next
            onChange?.(next)
            message.success('长图已上传')
          })
          .catch(() => message.error('长图上传失败'))
        return false
      }}
      onRemove={(file) => {
        const next = (valueRef.current ?? []).filter((u) => u !== file.url)
        valueRef.current = next
        onChange?.(next)
        return true
      }}
    >
      {(value?.length ?? 0) < max ? (
        <div>
          <PlusOutlined />
          <div style={{ marginTop: 8 }}>上传长图</div>
        </div>
      ) : null}
    </Upload>
  )
}
