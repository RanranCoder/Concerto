import { lazy, Suspense, useEffect, useState } from 'react'
import { Button, Empty, Modal, Space, Spin } from 'antd'
import { DownloadOutlined, EyeOutlined } from '@ant-design/icons'
import { downloadFile } from '@/api/file'
import DOMPurify from 'dompurify'
import { fetchFileBlob } from './httpBlob'

/**
 * 文件预览组件（PRD F03/F04）
 * 支持图片、PDF、Word 和 Excel。
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

const PdfFilePreview = lazy(() => import('./PdfFilePreview'))
const excelPreviewLimit = 10 * 1024 * 1024

export default function FilePreviewModal({ open, onClose, fileId, fileName, extra }: FilePreviewProps) {
  const ext = extOf(fileName)
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
      {open && <FilePreviewContent key={`${fileId}:${ext}`} fileId={fileId} fileName={fileName} ext={ext} />}
    </Modal>
  )
}

function FilePreviewContent({ fileId, fileName, ext }: { fileId: number; fileName: string; ext: string }) {
  const [loading, setLoading] = useState(true)
  const [wordHtml, setWordHtml] = useState<string | null>(null)
  const [excelRows, setExcelRows] = useState<{ headers: string[]; rows: string[][] } | null>(null)
  const [error, setError] = useState<string | null>(null)

  const isImage = ['png', 'jpg', 'jpeg', 'gif', 'webp', 'bmp'].includes(ext)
  const isPdf = ext === 'pdf'
  const isWord = ext === 'docx' || ext === 'doc'
  const isExcel = ext === 'xlsx' || ext === 'xls'

  const [blobUrl, setBlobUrl] = useState<string | null>(null)
  const [pdf, setPdf] = useState<Blob | null>(null)

  useEffect(() => {
    if (!isImage && !isPdf && !isWord && !isExcel) {
      setLoading(false)
      return
    }

    const controller = new AbortController()
    let objectUrl: string | null = null
    async function load() {
      try {
        const blob = await fetchFileBlob(fileId, controller.signal)
        if (controller.signal.aborted) return
        if (isImage) {
          objectUrl = URL.createObjectURL(blob)
          setBlobUrl(objectUrl)
        } else if (isPdf) {
          if (blob.type !== 'application/pdf') {
            setError('文件内容与 PDF 格式不符，请下载后查看')
            return
          }
          setPdf(blob)
        } else if (isWord) {
          const { renderAsync } = await import('docx-preview')
          if (controller.signal.aborted) return
          const holder = document.createElement('div')
          await renderAsync(blob, holder, undefined, {
            className: 'file-preview-word',
            inWrapper: true,
            ignoreWidth: true,
            ignoreHeight: true,
            breakPages: false,
            useBase64URL: true,
          })
          if (!controller.signal.aborted) setWordHtml(DOMPurify.sanitize(holder.innerHTML))
        } else {
          if (blob.size > excelPreviewLimit) {
            setError('Excel 文件超过 10 MB，请下载后查看')
            return
          }
          const [XLSX, buffer] = await Promise.all([import('xlsx'), blob.arrayBuffer()])
          if (controller.signal.aborted) return
          const wb = XLSX.read(buffer, { type: 'array', sheets: 0, sheetRows: 501 })
          const sheet = wb.Sheets[wb.SheetNames[0]]
          if (!sheet?.['!ref']) {
            setError('没有可预览的工作表内容')
            return
          }
          const range = XLSX.utils.decode_range(sheet['!ref'])
          range.e.c = Math.min(range.e.c, range.s.c + 99)
          range.e.r = Math.min(range.e.r, range.s.r + 500)
          const [headers = [], ...rows] = XLSX.utils.sheet_to_json<string[]>(sheet, { header: 1, raw: false, defval: '', range })
          setExcelRows({ headers, rows })
        }
      } catch {
        if (!controller.signal.aborted) setError('文件预览失败，请下载后查看')
      } finally {
        if (!controller.signal.aborted) setLoading(false)
      }
    }
    void load()
    return () => {
      controller.abort()
      if (objectUrl) URL.revokeObjectURL(objectUrl)
    }
  }, [fileId, isImage, isPdf, isWord, isExcel])

  return (
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
        {!loading && !error && pdf && (
          <Suspense fallback={<Spin />}><PdfFilePreview blob={pdf} /></Suspense>
        )}
        {!loading && !error && isWord && wordHtml && (
          <div className="file-preview-word-wrap" dangerouslySetInnerHTML={{ __html: wordHtml }} />
        )}
        {!loading && !error && isExcel && excelRows && (
          <table style={{ width: '100%', borderCollapse: 'collapse', fontSize: 14 }}>
            <thead>
              <tr>
                {excelRows.headers.map((h, i) => (
                  <th key={i} style={{ border: '1px solid var(--border)', padding: '6px 8px', background: 'var(--soft)' }}>
                    {h}
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {excelRows.rows.map((r, ri) => (
                <tr key={ri}>
                  {excelRows.headers.map((_, ci) => (
                    <td key={ci} style={{ border: '1px solid var(--border)', padding: '6px 8px' }}>
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
  )
}
