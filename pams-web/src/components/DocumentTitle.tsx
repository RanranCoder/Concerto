import { useEffect } from 'react'
import { useLocation } from 'react-router-dom'
import { resolveDocumentTitle } from '@/utils/pageTitle'

/**
 * 把当前路由同步到浏览器标签标题（「协奏-{标签页名}」）。
 * 挂在路由根布局上，登录页与登录后的页面都能覆盖到。
 */
export default function DocumentTitle() {
  const { pathname } = useLocation()

  useEffect(() => {
    document.title = resolveDocumentTitle(pathname)
  }, [pathname])

  return null
}
