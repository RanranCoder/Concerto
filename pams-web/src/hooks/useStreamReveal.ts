import { useLayoutEffect, useRef } from 'react'

const segmenter = new Intl.Segmenter('zh', { granularity: 'grapheme' })
const graphemes = (text: string) => Array.from(segmenter.segment(text), s => s.segment)
const DURATION = 90
const STAGGER = 8
const FRAME_INTERVAL = 16
const LOOK_AHEAD = 32
const MAX_LAG = 320
const FINISH_LAG = 100
const MAX_EFFECTS = 12

// Replies/reasoning share one clock. React and Markdown parsing never run on
// animation frames; only the small reveal frontier changes in the DOM.
const players = new Set<(now: number) => boolean>()
let frame = 0
function play(player: (now: number) => boolean) {
  players.add(player)
  const tick = () => {
    const now = performance.now()
    frame = 0
    for (const update of players) if (!update(now)) players.delete(update)
    if (players.size) frame = requestAnimationFrame(tick)
  }
  if (!frame) frame = requestAnimationFrame(tick)
  return () => {
    players.delete(player)
    if (!players.size && frame) { cancelAnimationFrame(frame); frame = 0 }
  }
}

type Letter = { text: string; id: number; deadline?: number }
type Run = { element: HTMLElement; letters: Letter[]; atomic: boolean; end: number }
type Part = { text: string; key?: string; deadline?: number; hidden?: boolean; bucket?: number }
const partKeys = new WeakMap<Node, string>()

/** Patch a text run without replacing its settled prefix or active animations. */
function paint(run: Run, now: number, visible: boolean, budget: { remaining: number }) {
  const { element, letters } = run
  if (run.atomic) {
    const deadline = letters[0]?.deadline
    const animate = visible && deadline !== undefined && deadline + DURATION > now &&
      deadline <= now + LOOK_AHEAD && budget.remaining > 0
    const pending = deadline !== undefined && (!visible || deadline > now + (animate ? LOOK_AHEAD : 0))
    element.classList.toggle('pams-stream-pending', pending)
    element.tabIndex = pending ? -1 : 0
    if (pending) element.setAttribute('aria-hidden', 'true')
    else element.removeAttribute('aria-hidden')
    if (animate) {
      budget.remaining--
      if (!element.classList.contains('pams-stream-char')) {
        element.style.animationDelay = `${deadline - now}ms`
        element.classList.add('pams-stream-char')
      }
    } else {
      element.classList.remove('pams-stream-char')
      element.style.removeProperty('animation-delay')
    }
    return
  }
  const parts: Part[] = []
  for (const letter of letters) {
    const { text, deadline, id } = letter
    const last = parts.at(-1)
    const bucket = deadline === undefined ? undefined : Math.floor(deadline / FRAME_INTERVAL)
    const animated = visible && deadline !== undefined && deadline + DURATION > now && deadline <= now + LOOK_AHEAD &&
      (budget.remaining > 0 || (last?.deadline !== undefined && last.bucket === bucket))
    const hidden = deadline !== undefined && (!visible || deadline > now + (animated ? LOOK_AHEAD : 0))
    // At high input rates, adjacent glyphs in one display frame share an
    // effect. This keeps the flow continuous without hundreds of blur layers.
    if (animated && last?.deadline !== undefined && last.bucket === bucket) { last.text += text; continue }
    if (animated) budget.remaining--
    if (!animated && last && last.deadline === undefined && Boolean(last.hidden) === hidden) last.text += text
    else parts.push(animated ? { text, key: `char-${id}`, deadline, bucket } : { text, hidden })
  }
  const counts = { pending: 0, text: 0 }
  const keys = parts.map(part => part.key ?? (part.hidden ? `pending-${counts.pending++}` : `text-${counts.text++}`))
  const wanted = new Set(keys)
  // Remove expired glyphs first. Moving a still-active CSS animation with
  // insertBefore can restart it in browsers, so active nodes stay connected.
  for (const node of Array.from(element.childNodes)) if (!wanted.has(partKeys.get(node) ?? '')) node.remove()
  const existing = new Map(Array.from(element.childNodes, node => [partKeys.get(node) ?? '', node]))
  let cursor = element.firstChild
  parts.forEach((part, index) => {
    const key = keys[index]
    let node = existing.get(key)
    if (!node) {
      if (part.deadline !== undefined || part.hidden) {
        const span = document.createElement('span')
        span.textContent = part.text
        if (part.hidden) { span.className = 'pams-stream-pending'; span.setAttribute('aria-hidden', 'true') }
        else { span.className = 'pams-stream-char'; span.style.animationDelay = `${part.deadline! - now}ms` }
        node = span
      } else node = document.createTextNode(part.text)
      partKeys.set(node, key)
    } else if (node.textContent !== part.text) node.textContent = part.text
    if (node !== cursor) element.insertBefore(node, cursor)
    cursor = node.nextSibling
  })
  while (cursor) { const next = cursor.nextSibling; cursor.remove(); cursor = next }
}

/** Preserve Markdown elements across chunks. Each queued suffix stays one
 * hidden node instead of creating an animation for every future character. */
function patchTree(root: HTMLElement, target: Node, runs: Run[], makeRun: (element: HTMLElement, text: string, atomic: boolean) => Run) {
  let current = root.firstChild
  for (const desired of Array.from(target.childNodes)) {
    let element: HTMLElement
    if (desired instanceof Text) {
      element = current instanceof HTMLElement && current.hasAttribute('data-pams-run') ? current : document.createElement('span')
      element.setAttribute('data-pams-run', '')
      if (element !== current) root.insertBefore(element, current)
      runs.push(makeRun(element, desired.data, false))
    } else if (desired instanceof HTMLElement) {
      element = current instanceof HTMLElement && current.tagName === desired.tagName &&
        !current.hasAttribute('data-pams-run') && current.classList.contains('lingsi-cite') === desired.classList.contains('lingsi-cite')
        ? current : desired.cloneNode(false) as HTMLElement
      if (element !== current) root.insertBefore(element, current)
      const atomic = desired.classList.contains('lingsi-cite')
      for (const attr of Array.from(element.attributes)) {
        if (atomic && ['style', 'class', 'aria-hidden', 'tabindex'].includes(attr.name)) continue
        if (!desired.hasAttribute(attr.name)) element.removeAttribute(attr.name)
      }
      for (const attr of Array.from(desired.attributes)) {
        if (atomic && ['class', 'tabindex'].includes(attr.name)) continue
        if (element.getAttribute(attr.name) !== attr.value) element.setAttribute(attr.name, attr.value)
      }
      if (atomic) {
        if (element.textContent !== desired.textContent) element.textContent = desired.textContent
        runs.push(makeRun(element, `[${desired.dataset.idx}]`, true))
      } else patchTree(element, desired, runs, makeRun)
    } else continue
    current = element.nextSibling
  }
  while (current) { const next = current.nextSibling; current.remove(); current = next }
}

/** Absolute deadlines survive chunking, completion and reasoning visibility.
 * Raw reasoning is always literal text, never interpreted as HTML. */
export function useStreamReveal(html: string, streaming = false,
  { plainText = false, visible = true, sourceText, settled = false }:
  { plainText?: boolean; visible?: boolean; sourceText?: string; settled?: boolean } = {}) {
  const ref = useRef<HTMLSpanElement>(null)
  const previous = useRef<{ chars: string[]; ids: number[]; deadlines: Map<number, number>; streaming: boolean }>({
    chars: [], ids: [], deadlines: new Map(), streaming: false,
  })
  useLayoutEffect(() => {
    const root = ref.current
    if (!root) {
      previous.current = { chars: [], ids: [], deadlines: new Map(), streaming: false }
      return
    }
    const now = performance.now()
    const source = sourceText !== undefined ? graphemes(sourceText) : null
    const old = previous.current
    const oldIndices = new Map(old.ids.map((id, index) => [id, index]))
    const chars: string[] = [], ids: number[] = [], deadlines = new Map<number, number>()
    const motion = window.matchMedia?.('(prefers-reduced-motion: reduce)')
    const reduced = motion?.matches
    let cursor = 0, next = -Infinity
    const makeRun = (element: HTMLElement, text: string, atomic: boolean): Run => {
      const letters = graphemes(text).map(char => {
        const index = chars.length
        const found = source?.indexOf(char, cursor) ?? index
        const id = found < 0 ? -index - 1 : found
        if (found >= 0) cursor = found + 1
        chars.push(char); ids.push(id)
        let deadline: number | undefined
        if (!reduced && !settled) {
          const oldIndex = oldIndices.get(id)
          if (oldIndex !== undefined && old.chars[oldIndex] === char) {
            const prior = old.deadlines.get(oldIndex)
            if (prior !== undefined && prior + DURATION > now) deadline = prior >= now ? Math.max(prior, next) : prior
          } else if (streaming || old.streaming) deadline = Math.max(now, next)
          if (deadline !== undefined) { deadlines.set(index, deadline); next = Math.max(next, deadline + STAGGER) }
        }
        return { text: char, id, deadline }
      })
      return { element, letters, atomic, end: letters.reduce((end, l) => Math.max(end, l.deadline === undefined ? 0 : l.deadline + DURATION), 0) }
    }
    const runs: Run[] = []
    if (plainText) runs.push(makeRun(root, html, false))
    else {
      const template = document.createElement('template')
      template.innerHTML = html
      patchTree(root, template.content, runs, makeRun)
    }
    // Retain effects already at the frontier; accelerate only the unseen tail.
    // The UI follows incoming data within a bounded delay, rather than playing
    // an arbitrarily long recording after the real phase has already ended.
    const horizon = !streaming && old.streaming ? FINISH_LAG : MAX_LAG
    const future = runs.flatMap(run => run.letters).filter(letter => letter.deadline !== undefined && letter.deadline > now + LOOK_AHEAD)
    if (future.length && future[future.length - 1].deadline! > now + horizon) {
      const interval = (horizon - LOOK_AHEAD) / future.length
      future.forEach((letter, index) => { letter.deadline = now + LOOK_AHEAD + (index + 1) * interval })
      let index = 0
      for (const run of runs) {
        run.end = 0
        for (const letter of run.letters) {
          if (letter.deadline !== undefined) {
            deadlines.set(index, letter.deadline)
            run.end = Math.max(run.end, letter.deadline + DURATION)
          }
          index++
        }
      }
    }
    previous.current = { chars, ids, deadlines, streaming }
    const paintRuns = (list: Run[], time: number) => {
      const budget = {remaining:MAX_EFFECTS}
      for (const run of list) paint(run, time, visible, budget)
    }
    // Rebase hidden reasoning on toggles, without running its invisible queue.
    paintRuns(runs, now)
    if (!visible || !deadlines.size) return
    let nextPaint = now + FRAME_INTERVAL
    let pending = runs.filter(run => run.end > now)
    return play(time => {
      // The MediaQueryList is live: enabling reduced motion mid-reply must also
      // reveal the compact hidden suffix, not leave it waiting in the queue.
      if (motion?.matches) {
        deadlines.clear()
        for (const run of runs) {
          for (const letter of run.letters) letter.deadline = undefined
        }
        paintRuns(runs, time)
        return false
      }
      if (time < nextPaint) return true
      nextPaint = time + FRAME_INTERVAL
      paintRuns(pending, time)
      pending = pending.filter(run => run.end > time)
      return pending.length > 0
    })
  }, [html, streaming, plainText, visible, sourceText, settled])
  return ref
}
