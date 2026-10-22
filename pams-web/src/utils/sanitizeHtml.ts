// XSS 防护：净化历史富文本和其他 HTML 展示内容。
// 允许基本排版标签（p/div/br/b/strong/table/tr/td/th/ul/ol/li/span…），
// 但禁止脚本类标签与一切事件属性，避免落库的恶意 HTML（img onerror 外带 token 等）在他人打开时执行。
// 注意：事件属性（onerror 等）本就不在 DOMPurify 默认 ALLOWED_ATTR 内，会被直接剥离；
// FORBID_ATTR 的 'on*' 仅为显式意图声明（DOMPurify 精确匹配，不改动时无害）。
// 不禁用 style 属性，编辑器「字号」功能依赖 span[style=font-size] 保留。
// vitest（node 环境）无 window，DOMPurify 降级为工厂函数无 .sanitize，捕获后走最小兜底净化。
import DOMPurify from 'dompurify'

export function sanitizeEditableHtml(html: string): string {
  const input = html || ''
  if (!input) return ''
  try {
    return DOMPurify.sanitize(input, {
      FORBID_TAGS: ['style', 'script', 'iframe', 'svg', 'form', 'object', 'embed', 'link', 'meta', 'base', 'math'],
      FORBID_ATTR: ['on*'],
    })
  } catch {
    // node 测试环境：DOMPurify 是工厂函数，退化为最小兜底净化（浏览器由上面真实净化兜底）
    return input
      .replace(/<\s*(script|style|iframe|object|embed|link|meta|base|form|svg|math)(?:\s[^>]*)?>[\s\S]*?<\s*\/\s*\1\s*>/gi, '')
      .replace(/<\s*\/?\s*(script|style|iframe|object|embed|link|meta|base|form|svg|math)(?:\s[^>]*)?>/gi, '')
      .replace(/\son[a-z]+\s*=\s*(?:"[^"]*"|'[^']*'|[^\s>]+)/gi, '')
  }
}
