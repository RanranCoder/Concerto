import { useState } from 'react'
import { Upload, message } from 'antd'
import { InboxOutlined } from '@ant-design/icons'
import { uploadRoster } from '@/api/signin'

interface SigninRosterUploadProps {
  activityId: number
  /** 上传成功回调（返回新增人数），父级刷新汇总与名单列表 */
  onUploaded: (added: number) => void
}

/**
 * 应签名单 Excel 拖拽上传（.xlsx/.xls）。
 * 自定义 customRequest 调 uploadRoster（multipart：activityId + file），
 * 成功提示「导入 N 人」并回调 onUploaded 触发父级刷新。
 */
export default function SigninRosterUpload({ activityId, onUploaded }: SigninRosterUploadProps) {
  const [uploading, setUploading] = useState(false)

  return (
    <Upload.Dragger
      accept=".xlsx,.xls"
      maxCount={1}
      showUploadList={false}
      disabled={uploading}
      customRequest={async ({ file, onSuccess, onError }) => {
        setUploading(true)
        try {
          const res = await uploadRoster(activityId, file as File)
          const added = res?.added ?? 0
          message.success(`导入 ${added} 人`)
          onUploaded(added)
          onSuccess?.(res)
        } catch (e) {
          onError?.(e as Error)
        } finally {
          setUploading(false)
        }
      }}
    >
      <p className="ant-upload-drag-icon">
        <InboxOutlined />
      </p>
      {uploading ? (
        <p className="ant-upload-text">上传解析中…</p>
      ) : (
        <p className="ant-upload-text">点击或拖拽 Excel 名单到此处上传</p>
      )}
      <p className="ant-upload-hint">
        表头需与核验字段名一致（如 姓名 / 学号），必填列缺失将报错。每行一位应签人。
      </p>
    </Upload.Dragger>
  )
}
