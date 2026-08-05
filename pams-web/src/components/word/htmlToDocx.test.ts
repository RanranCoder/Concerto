import { describe, it, expect, vi } from 'vitest'
import { DOMParser } from '@xmldom/xmldom'
import { htmlToDocx } from './htmlToDocx'

// node 环境注入 DOMParser，验证浏览器 htmlToDocx 路径（块级/行内/表格/图片）
;(globalThis as unknown as { DOMParser: typeof DOMParser }).DOMParser = DOMParser

describe('htmlToDocx browser path', () => {
  it('maps blocks and inline runs to docx paragraphs', async () => {
    const mod = await import('docx')
    const ctx = { mod }
    const html = '<h2>标题</h2><p>第一段 <strong>加粗</strong> <u>下划线</u> <span style="color:#c0392b">红字</span></p><ul><li>条目A</li><li>条目B</li></ul>'
    const paragraphs = await htmlToDocx(html, ctx)
    expect(paragraphs.length).toBeGreaterThanOrEqual(4)
    // 首段为标题
    expect(paragraphs[0]).toBeInstanceOf(mod.Paragraph)
  })

  it('maps table to docx Table', async () => {
    const mod = await import('docx')
    const ctx = { mod }
    const html = '<table><tr><th>物品</th><th>数量</th></tr><tr><td>横幅</td><td>1</td></tr></table>'
    const paragraphs = await htmlToDocx(html, ctx)
    expect(paragraphs.some((p) => p instanceof mod.Table)).toBe(true)
  })

  it('skips image when fetch fails, keeps text', async () => {
    const mod = await import('docx')
    const ctx = {
      mod,
      fetchBlob: vi.fn(async () => null),
    }
    const html = '<p>文字 <img src="/api/files/1/download" /> 结尾</p>'
    const paragraphs = await htmlToDocx(html, ctx)
    expect(ctx.fetchBlob).toHaveBeenCalledWith('/api/files/1/download')
    // 图片失败仍生成段落（文字保留）
    expect(paragraphs.length).toBeGreaterThanOrEqual(1)
  })

  it('maps image to ImageRun when fetch succeeds', async () => {
    const mod = await import('docx')
    // 1x1 png
    const png = new Uint8Array(Buffer.from('89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c4890000000d4944415478da63fccfc0f01f00050001fff21f740000000049454e44ae426082', 'hex'))
    const ctx = {
      mod,
      fetchBlob: vi.fn(async () => new Blob([png], { type: 'image/png' })),
    }
    const html = '<p><img src="/api/files/5/download" width="120" height="90" /></p>'
    const paragraphs = await htmlToDocx(html, ctx)
    expect(paragraphs.length).toBeGreaterThanOrEqual(1)
    // 生成有效 docx（含图片关系）
    const { Document, Packer } = mod
    const doc = new Document({ sections: [{ children: paragraphs }] })
    const blob = await Packer.toBlob(doc)
    expect(blob.size).toBeGreaterThan(2000)
  }, 20000)

  it('node fallback (no DOMParser) extracts plain text', async () => {
    // 临时移除 DOMParser 验证退化为纯文本段
    const saved = (globalThis as unknown as { DOMParser?: typeof DOMParser }).DOMParser
    ;(globalThis as unknown as { DOMParser?: typeof DOMParser }).DOMParser = undefined as unknown as typeof DOMParser
    try {
      const mod = await import('docx')
      const paragraphs = await htmlToDocx('<p><b>加粗</b> 文本</p><div>第二行</div>', { mod })
      expect(paragraphs.length).toBeGreaterThanOrEqual(1)
      expect(paragraphs[0]).toBeInstanceOf(mod.Paragraph)
    } finally {
      ;(globalThis as unknown as { DOMParser?: typeof DOMParser }).DOMParser = saved
    }
  })
})
