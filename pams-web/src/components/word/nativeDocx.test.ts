import { describe, expect, it } from 'vitest'
import JSZip from 'jszip'
import { validateDocx } from './nativeDocx'

describe('native DOCX import validation', () => {
  it('accepts a DOCX container without translating its XML to HTML', async () => {
    const zip = new JSZip()
    zip.file('[Content_Types].xml', '<Types/>')
    zip.file('word/document.xml', '<document>原始复杂文档</document>')
    const bytes = await zip.generateAsync({ type: 'uint8array' })
    const original = new Blob([bytes])
    await expect(validateDocx(original)).resolves.toBeUndefined()
    expect(await original.arrayBuffer()).toEqual(bytes.buffer)
  })
  it('rejects renamed text and unrelated ZIP files', async () => {
    await expect(validateDocx(new Blob(['not docx']))).rejects.toThrow()
    const zip = new JSZip()
    zip.file('content.txt', 'not a word document')
    await expect(validateDocx(new Blob([await zip.generateAsync({ type: 'uint8array' })]))).rejects.toThrow('无效')
  })
  it('rejects empty documents', async () => {
    await expect(validateDocx(new Blob())).rejects.toThrow()
  })
})
