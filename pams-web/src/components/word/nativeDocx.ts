/** Validate the container before opening it. Imports stay as native DOCX bytes. */
export async function validateDocx(blob: Blob) {
  if (!blob.size || blob.size > 20 * 1024 * 1024) throw new Error('DOCX 文件应小于 20MB')
  const { default: JSZip } = await import('jszip')
  const zip = await JSZip.loadAsync(await blob.arrayBuffer())
  if (!zip.file('[Content_Types].xml') || !zip.file('word/document.xml')) throw new Error('无效的 DOCX 文件')
}
