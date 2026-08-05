import { useEffect, useMemo, useState } from 'react'
import { Button, Empty, Modal, Space, Spin } from 'antd'
import { DownloadOutlined, EyeOutlined } from '@ant-design/icons'
import { downloadFile } from '@/api/file'

/**
 * 文件预览组件（PRD F03/F04）
 * 支持：图片（img）、PDF（iframe）、Word docx（docx-preview）、Excel xlsx（xlsx → HTML 表格）
 * 其他格式：显示文件信息 + 下载按钮
 */

interface FilePreviewProps {
  open: boolean
  onClose: () => void
  fileId: number
  fileName: string
  /** 附加操作按钮（如模板库"使用此模板"） */
  extra?: React.ReactNode
}

function extOf(name: string): string {
  const i = name.lastIndexOf('.')
  return i >= 0 ? name.slice(i + 1).toLowerCase() : ''
}

export function previewableExt(name: string): boolean {
  return ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp', 'pdf', 'docx', 'doc', 'xlsx', 'xls'].includes(extOf(name))
}

export default function FilePreviewModal({ open, onClose, fileId, fileName, extra }: FilePreviewProps) {
  const ext = useMemo(() => extOf(fileName), [fileName])
  const [loading, setLoading] = useState(true)
  const [wordHtml, setWordHtml] = useState<string | null>(null)
  const [excelRows, setExcelRows] = useState<{ headers: string[]; rows: string[][] } | null>(null)
  const [error, setError] = useState<string | null>(null)

  const isImage = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp'].includes(ext)
  const isPdf = ext === 'pdf'
  const isWord = ext === 'docx' || ext === 'doc'
  const isExcel = ext === 'xlsx' || ext === 'xls'

  // 图片/PDF：拉 blob 生成临时 URL（带 JWT，避免依赖静态映射路径）
  const [blobUrl, setBlobUrl] = useState<string | null>(null)

  useEffect(() => {
    if (!open) return
    setLoading(true)
    setError(null)
    setWordHtml(null)
    setExcelRows(null)
    setBlobUrl(null)

    const revoke = (url: string | null) => {
      if (url) URL.revokeObjectURL(url)
    }

    if (isImage || isPdf) {
      import('./httpBlob')
        .then((m) => m.fetchFileBlob(fileId))
        .then((blob) => setBlobUrl(URL.createObjectURL(blob)))
        .catch(() => setError('文件加载失败，请下载查看'))
        .finally(() => setLoading(false))
    } else if (isWord) {
      // docx-preview 动态加载，拉 blob 渲染
      Promise.all([
        import('docx-preview'),
        import('./httpBlob').then((m) => m.fetchFileBlob(fileId)),
      ])
        .then(async ([mod, blob]) => {
          const holder = document.createElement('div')
          await mod.renderAsync(blob, holder, undefined, {
            className: 'file-preview-word',
            inWrapper: true,
            ignoreWidth: true,
            ignoreHeight: true,
            breakPages: false,
          })
          setWordHtml(holder.innerHTML)
        })
        .catch(() => setError('Word 预览失败，请下载查看'))
        .finally(() => setLoading(false))
    } else if (isExcel) {
      Promise.all([import('xlsx'), import('./httpBlob').then((m) => m.fetchFileBlob(fileId))])
        .then(async ([XLSX, blob]) => {
          const buf = new Uint8Array(await blob.arrayBuffer())
          const wb = XLSX.read(buf, { type: 'array' })
          const sheet = wb.Sheets[wb.SheetNames[0]]
          const matrix: string[][] = XLSX.utils.sheet_to_json(sheet, { header: 1, raw: false, defval: '' })
          const headers = matrix[0] ?? []
          const rows = matrix.slice(1).slice(0, 500)
          setExcelRows({ headers, rows })
        })
        .catch(() => setError('Excel 预览失败，请下载查看'))
        .finally(() => setLoading(false))
    } else {
      setLoading(false)
    }
    return () => revoke(blobUrl)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, fileId, ext])

  return (
    <Modal
      open={open}
      onCancel={onClose}
      title={
        <Space>
          <EyeOutlined />
          <span style={{ wordBreak: 'break-all' }}>{fileName}</span>
        </Space>
      }
      width={Math.min(window.innerWidth - 48, 1000)}
      style={{ top: 24 }}
      footer={
        <Space>
          {extra}
          <Button icon={<DownloadOutlined />} type="primary" onClick={() => downloadFile(fileId, fileName)}>
            下载
          </Button>
          <Button onClick={onClose}>关闭</Button>
        </Space>
      }
    >
      <div style={{ minHeight: 320, maxHeight: '70vh', overflow: 'auto' }}>
        {loading && (
          <div style={{ textAlign: 'center', padding: 80 }}>
            <Spin />
          </div>
        )}
        {!loading && error && (
          <Empty description={error}>
            <Button icon={<DownloadOutlined />} onClick={() => downloadFile(fileId, fileName)}>
              下载文件
            </Button>
          </Empty>
        )}
        {!loading && !error && isImage && blobUrl && (
          <img src={blobUrl} alt={fileName} style={{ maxWidth: '100%', display: 'block', margin: '0 auto' }} />
        )}
        {!loading && !error && isPdf && blobUrl && (
          <iframe src={blobUrl} title={fileName} style={{ width: '100%', height: '70vh', border: 'none' }} />
        )}
        {!loading && !error && isWord && wordHtml && (
          <div className="file-preview-word-wrap" dangerouslySetInnerHTML={{ __html: wordHtml }} />
        )}
        {!loading && !error && isExcel && excelRows && (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 13 }}>
            <thead>
              <tr>
                {excelRows.headers.map((h, i) => (
                  <th key={i} style={{ border: '1px solid var(--color-border)', padding: '6px 8px', background: 'var(--color-bg-2)' }}>
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {excelRows.rows.map((r, ri) => (
                <tr key={ri}>
                  {excelRows.headers.map((_, ci) => (
                    <td key={ci} style={{ border: '1px solid var(--color-border)', padding: '6px 8px' }}>
                      {r[ci] ?? ''}
                    </td>
                  ))}
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {!loading && !error && !isImage && !isPdf && !isWord && !isExcel && (
          <Empty description={`暂不支持 ${ext || '未知'} 格式在线预览`}>
            <Button icon={<DownloadOutlined />} onClick={() => downloadFile(fileId, fileName)}>
              下载文件
            </Button>
          </Empty>
        )}
      </div>
    </Modal>
  )
}
