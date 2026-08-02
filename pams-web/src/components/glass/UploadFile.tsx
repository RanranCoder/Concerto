import { useState } from 'react'
import { Upload, message } from 'antd'
import { InboxOutlined } from '@ant-design/icons'
import { uploadFile } from '@/api/file'

interface UploadFileProps {
  bizType?: string
  /** 上传成功回调（返回新文件 fileId） */
  onUploaded: (fileId: number) => void
}

/** 拖拽上传组件：antd Upload.Dragger + 进度，成功后回调 fileId */
export default function UploadFile({ bizType, onUploaded }: UploadFileProps) {
  const [uploading, setUploading] = useState(false)
  const [percent, setPercent] = useState(0)

  return (
    <Upload.Dragger
      maxCount={1}
      showUploadList={false}
      disabled={uploading}
      customRequest={async ({ file, onSuccess, onError }) => {
        setUploading(true)
        setPercent(0)
        try {
          const rec = await uploadFile(file as File, bizType, setPercent)
          onUploaded(rec.id)
          message.success(`文件 ${rec.filename} 上传成功`)
          onSuccess?.(rec)
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
        <p className="ant-upload-text">上传中 {percent}%</p>
      ) : (
        <p className="ant-upload-text">点击或拖拽文件到此处上传</p>
      )}
    </Upload.Dragger>
  )
}
