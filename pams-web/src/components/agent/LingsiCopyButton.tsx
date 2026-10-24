import { useEffect, useRef, useState } from 'react'
import { App, Tooltip } from 'antd'
import { Check, Copy } from './LingsiIcons'

interface Props { text: string; label?: string; size?: number }

/** Shared copy feedback: repeated clicks restart the 2.5s clock; unmount cancels it. */
export default function LingsiCopyButton({ text, label = '复制', size = 14 }: Props) {
  const { message } = App.useApp()
  const [copied, setCopied] = useState(false)
  const timer = useRef<ReturnType<typeof setTimeout>>()
  const mounted = useRef(true)
  useEffect(() => {
    mounted.current = true
    return () => { mounted.current = false; clearTimeout(timer.current) }
  }, [])
  useEffect(() => { clearTimeout(timer.current); setCopied(false) }, [text])

  const copy = async () => {
    if (!text.trim()) return
    try {
      if (navigator.clipboard?.writeText) await navigator.clipboard.writeText(text)
      else {
        const previous = document.activeElement as HTMLElement | null
        const field = document.createElement('textarea')
        field.value = text
        field.style.cssText = 'position:fixed;opacity:0;pointer-events:none'
        document.body.appendChild(field)
        try {
          field.select()
          if (!document.execCommand('copy')) throw new Error('Copy rejected')
        } finally { field.remove(); previous?.focus() }
      }
      if (!mounted.current) return
      clearTimeout(timer.current)
      setCopied(true)
      timer.current = setTimeout(() => setCopied(false), 2500)
    } catch {
      if (mounted.current) message.warning('复制失败，请手动选择文本')
    }
  }

  return <Tooltip title={copied ? '已复制' : label}>
    <button type="button" className="lingsi-icon-btn pams-copy" data-copied={copied}
      aria-label={copied ? '已复制' : label} onClick={() => void copy()}>
      <span className="pams-copy-original"><Copy size={size} /></span>
      <span className="pams-copy-check"><Check size={size} /></span>
      <span className="pams-sr-only" role="status">{copied ? '已复制' : ''}</span>
    </button>
  </Tooltip>
}
