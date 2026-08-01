import { Modal, type ModalProps } from 'antd'
import type { ReactNode } from 'react'

interface GlassModalProps extends ModalProps {
  children?: ReactNode
}

export default function GlassModal({ className, children, ...props }: GlassModalProps) {
  return (
    <Modal
      className={`glass-modal${className ? ` ${className}` : ''}`}
      destroyOnClose
      footer={null}
      {...props}
    >
      {children}
    </Modal>
  )
}
