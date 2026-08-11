import { App } from 'antd'

type AppApi = ReturnType<typeof App.useApp>

let message!: AppApi['message']
let notification!: AppApi['notification']
let modal!: AppApi['modal']

/** 挂载在 <AntApp> 内，把带主题的 message/notification/modal 实例捕获到模块级，供非组件代码（如 axios 拦截器）使用 */
export function FeedbackBridge() {
  const api = App.useApp()
  message = api.message
  notification = api.notification
  modal = api.modal
  return null
}

export { message, notification, modal }
