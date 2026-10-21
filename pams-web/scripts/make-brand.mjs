/** V1.7 位图方案的工程矢量稿；统一轮廓和规范色值，非字体几何定稿。 */
import { mkdirSync, writeFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { resolve } from 'node:path'
import { createRequire } from 'node:module'

// PNG 导出使用 Codex bundled sharp，也可传入独立安装的 sharp 所在目录。
if (!process.env.BRAND_NODE_MODULES) throw new Error('Set BRAND_NODE_MODULES to the directory containing sharp')
const require = createRequire(resolve(process.env.BRAND_NODE_MODULES, 'brand-loader.cjs'))
const sharp = require('sharp')
const root = fileURLToPath(new URL('../public/', import.meta.url))
mkdirSync(resolve(root, 'brand'), { recursive: true })
const blue = '#1668DC', lime = '#C7F36B', ink = '#17243A'
// 双符杠采用封闭填充路径，内孔与下方开口保持清楚；音符头的位置固定。
const shape = (main, accent) => `<path fill="${main}" fill-rule="evenodd" d="M342 805V503Q342 461 383 428C570 287 856 196 1030 196C1074 196 1102 217 1102 245C1102 273 1078 291 1035 297V710H968V431C780 462 562 533 409 622V825ZM409 497V551C583 456 783 389 968 354V290C780 323 562 394 409 497Z"/><ellipse fill="${main}" cx="352" cy="929" rx="215" ry="119" transform="rotate(-27 352 929)"/><ellipse fill="${accent}" cx="903" cy="804" rx="215" ry="119" transform="rotate(-27 903 804)"/>`
const svg = (body, viewBox = '-60 -60 1374 1374') => `<svg xmlns="http://www.w3.org/2000/svg" width="1254" height="1254" viewBox="${viewBox}">${body}</svg>\n`
for (const [name, main, accent] of [
  ['concerto-symbol', blue, lime],
  ['concerto-symbol-dark', '#FFFFFF', lime],
  ['concerto-symbol-mono', ink, ink],
  ['concerto-symbol-white', '#FFFFFF', '#FFFFFF'],
]) writeFileSync(resolve(root, `brand/${name}.svg`), svg(shape(main, accent)))
// 小尺寸光学校正：加宽双符杠之间的负形，保留两枚音符头和符干。
const optical = `<path fill="var(--note, ${blue})" fill-rule="evenodd" d="M8 23V12Q8 10 10 9Q20 3 28 3Q30 3 30 5Q30 6 28 6V19H25V12Q17 14 11 18V23ZM11 11V14Q18 10 25 9V6Q17 8 11 11Z"/><ellipse fill="var(--note, ${blue})" cx="8" cy="25" rx="6" ry="3.5" transform="rotate(-27 8 25)"/><ellipse fill="${lime}" cx="25" cy="21" rx="6" ry="3.5" transform="rotate(-27 25 21)"/>`
const favicon = svg(`<style>@media(prefers-color-scheme:dark){:root{--note:#FFFFFF}}</style>${optical}`, '0 0 32 32')
writeFileSync(resolve(root, 'favicon.svg'), favicon)
await sharp(Buffer.from(svg(optical, '0 0 32 32'))).resize(32, 32).png().toFile(resolve(root, 'favicon.png'))
await sharp(Buffer.from(svg(`<path fill="${ink}" d="M-60-60h1374v1374H-60z"/>${shape('#FFFFFF', lime)}`))).resize(180, 180).png().toFile(resolve(root, 'apple-touch-icon.png'))
console.log('Generated Concerto V1.7 SVG variants, favicon and touch icon.')
