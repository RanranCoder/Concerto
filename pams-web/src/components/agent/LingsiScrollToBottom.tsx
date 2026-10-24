import { memo, useEffect, useRef, useState, type RefObject } from 'react'

interface Props {
  scrollRef: RefObject<HTMLDivElement>
  generating: boolean
}

/** A local scroll subscription avoids rerendering the conversation on scrolling. */
function LingsiScrollToBottom({ scrollRef, generating }: Props) {
  const [away, setAway] = useState(false)
  const following = useRef(false)

  useEffect(() => {
    const el = scrollRef.current
    following.current = false
    if (!el || !generating) { setAway(false); return }
    let frame = 0
    let lastHeight = el.scrollHeight
    const check = () => {
      frame = 0
      const height = el.scrollHeight
      // Clicking explicitly opts into following this generation. User gestures
      // cancel it; ordinary incoming chunks never pull a reader down on their own.
      if (following.current && height !== lastHeight) el.scrollTo({top:height, behavior:'instant'})
      lastHeight = height
      setAway(height - el.clientHeight - el.scrollTop > 24)
    }
    const schedule = () => { if (!frame) frame = requestAnimationFrame(check) }
    const stopFollowing = () => { following.current = false }
    const wheel = (event: WheelEvent) => { if (event.deltaY < 0) stopFollowing() }
    const key = (event: KeyboardEvent) => {
      if (['ArrowUp', 'PageUp', 'Home'].includes(event.key) || (event.key === ' ' && event.shiftKey)) stopFollowing()
    }
    const observer = new ResizeObserver(schedule)
    observer.observe(el)
    const content = el.querySelector('.lingsi-stream-inner')
    if (content) observer.observe(content)
    el.addEventListener('scroll', schedule, {passive:true})
    el.addEventListener('wheel', wheel, {passive:true})
    el.addEventListener('touchstart', stopFollowing, {passive:true})
    el.addEventListener('pointerdown', stopFollowing, {passive:true})
    el.addEventListener('keydown', key)
    schedule()
    return () => {
      if (frame) cancelAnimationFrame(frame)
      observer.disconnect()
      el.removeEventListener('scroll', schedule)
      el.removeEventListener('wheel', wheel)
      el.removeEventListener('touchstart', stopFollowing)
      el.removeEventListener('pointerdown', stopFollowing)
      el.removeEventListener('keydown', key)
      following.current = false
    }
  }, [generating, scrollRef])

  if (!generating || !away) return null
  return (
    <button type="button" className="lingsi-scroll-bottom" aria-label="回到底部"
      title="正在生成，回到底部" onClick={() => {
        following.current = true
        scrollRef.current?.scrollTo({top:scrollRef.current.scrollHeight,
          behavior:window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'})
      }}>
      <svg className="lingsi-scroll-bottom-ring" width="48" height="48" viewBox="0 0 48 48" fill="none" aria-hidden="true">
        <circle className="lingsi-scroll-bottom-track" cx="24" cy="24" r="22" />
        <circle className="lingsi-scroll-bottom-progress" cx="24" cy="24" r="22" strokeDasharray="32 107" />
      </svg>
      <svg width="24" height="24" viewBox="0 0 24 24" fill="none" stroke="currentColor"
        strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
        <path d="M12 4v15m-6-6 6 6 6-6" />
      </svg>
    </button>
  )
}

export default memo(LingsiScrollToBottom)
