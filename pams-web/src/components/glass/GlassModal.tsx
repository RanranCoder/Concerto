import { Modal, type ModalProps } from 'antd'
import type { ReactNode } from 'react'

interface GlassModalProps extends ModalProps {
  children?: ReactNode
}

/**
 * 全局弹窗。destroyOnClose 在 antd 5.25 起弃用，改 destroyOnHidden（语义一致：关闭时卸载内容）。
 * 注意：改动影响全站弹窗，改动后需回归验证各弹窗表单回填（Task 7/14/21 minor）。
 */
export default function GlassModal({ className, children, ...props }: GlassModalProps) {
  return (
    <Modal
      className={`glass-modal${className ? ` ${className}` : ''}`}
      destroyOnHidden
      footer={null}
      {...props}
    >
      {children}
    </Modal>
  )
}
