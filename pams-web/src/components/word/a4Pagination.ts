/** A4 document geometry is independent from the application's theme and viewport. */
export const A4_WIDTH = (210 * 96) / 25.4
export const A4_HEIGHT = (297 * 96) / 25.4
export const DOCUMENT_CSS = `
html,body{margin:0;padding:0;width:210mm;color:#222;color-scheme:light;background:transparent}
*{box-sizing:border-box}
section.activity-docx{width:210mm!important;height:297mm!important;min-height:297mm!important;max-height:297mm!important;box-sizing:border-box!important;display:block!important;position:relative;margin:0 0 24px!important;background:#fff!important;color:#222;overflow:hidden;box-shadow:0 2px 8px #0002}
section.activity-docx:last-child{margin-bottom:0!important}
section.activity-docx>article{min-height:0!important;position:relative;overflow:visible}
section.activity-docx>footer{position:absolute!important;bottom:12.7mm;left:25.4mm;right:25.4mm;width:auto!important;margin:0!important}
section.activity-docx img{max-width:100%;object-fit:contain}
section.activity-docx table{max-width:100%}
`

export function paginateA4(doc: Document) {
  const splitText = (node: HTMLElement, index: number) => {
    const walker = doc.createTreeWalker(node, 4)
    let text: Node | null = null,
      remaining = index
    while ((text = walker.nextNode())) {
      const length = text.textContent?.length ?? 0
      if (remaining <= length) break
      remaining -= length
    }
    if (!text) return null
    const before = doc.createRange(),
      after = doc.createRange()
    before.selectNodeContents(node)
    before.setEnd(text, remaining)
    after.selectNodeContents(node)
    after.setStart(text, remaining)
    const first = node.cloneNode(false) as HTMLElement,
      rest = node.cloneNode(false) as HTMLElement
    first.append(before.cloneContents())
    rest.append(after.cloneContents())
    return [first, rest]
  }
  for (const section of [
    ...doc.querySelectorAll<HTMLElement>('section.activity-docx'),
  ]) {
    const article = section.querySelector<HTMLElement>(':scope > article')
    if (!article) continue
    const original = section.cloneNode(true) as HTMLElement,
      nodes = [...article.childNodes]
    article.replaceChildren()
    let page = section,
      content = article
    const newPage = () => {
      const next = original.cloneNode(true) as HTMLElement
      next.querySelector('article')!.replaceChildren()
      page.after(next)
      page = next
      content = next.querySelector<HTMLElement>('article')!
    }
    const available = () => {
      const style = doc.defaultView!.getComputedStyle(page)
      return (
        page.clientHeight -
        parseFloat(style.paddingTop) -
        parseFloat(style.paddingBottom) -
        (page.querySelector('header')?.getBoundingClientRect().height ?? 0) -
        (page.querySelector('footer')?.getBoundingClientRect().height ?? 0) -
        12
      )
    }
    for (const node of nodes) {
      if (node.nodeType === 1)
        (node as HTMLElement).querySelectorAll('img').forEach((img) => {
          img.style.maxHeight = Math.max(100, available() - 40) + 'px'
        })
      content.append(node)
      if (content.scrollHeight <= available() + 1) continue
      if (content.childNodes.length > 1) {
        node.remove()
        const heading = content.lastElementChild?.matches('h1,h2,h3')
          ? content.lastElementChild
          : null
        heading?.remove()
        newPage()
        if (heading) content.append(heading)
        content.append(node)
      }
      if (node.nodeType !== 1) continue
      const element = node as HTMLElement
      if (element.tagName === 'TABLE' && content.scrollHeight > available()) {
        const rows = [...element.querySelectorAll('tr')],
          header = element.querySelector('thead')?.cloneNode(true)
        let chunk = element
        rows.forEach((row) => row.remove())
        for (const row of rows) {
          chunk.append(row)
          if (
            content.scrollHeight > available() &&
            chunk.querySelectorAll('tr').length > 1
          ) {
            row.remove()
            newPage()
            chunk = element.cloneNode(false) as HTMLElement
            if (header) chunk.append(header.cloneNode(true))
            content.append(chunk)
            chunk.append(row)
          }
        }
      } else if (element.textContent && element.textContent.length > 1) {
        let remaining = element
        while (
          content.scrollHeight > available() + 1 &&
          (remaining.textContent?.length ?? 0) > 1
        ) {
          let low = 1,
            high = remaining.textContent!.length - 1,
            best = 0
          while (low <= high) {
            const mid = Math.floor((low + high) / 2),
              pair = splitText(remaining, mid)
            if (!pair) break
            remaining.replaceWith(pair[0])
            const fits = content.scrollHeight <= available()
            pair[0].replaceWith(remaining)
            if (fits) {
              best = mid
              low = mid + 1
            } else high = mid - 1
          }
          if (!best) break
          const pair = splitText(remaining, best)!
          remaining.replaceWith(pair[0])
          newPage()
          remaining = pair[1]
          content.append(remaining)
        }
      }
    }
  }
  return [...doc.querySelectorAll<HTMLElement>('section.activity-docx')]
}
