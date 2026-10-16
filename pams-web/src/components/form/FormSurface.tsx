import { useId, useLayoutEffect, useRef, type ReactNode } from 'react'
import { ArrowLeftOutlined } from '@ant-design/icons'
import { Button, Drawer, Space, type ModalProps } from 'antd'
import GlassModal from '@/components/glass/GlassModal'

export interface FormSurfaceProps extends Omit<ModalProps, 'footer' | 'width'> {
  mode?: 'dialog' | 'drawer' | 'workspace'
  description?: ReactNode
  width?: number | string
  footer?: ReactNode
}

/** Explicitly choose the surface by task, keeping each form's existing state and save handler. */
export default function FormSurface({
  mode = 'dialog',
  description,
  title,
  children,
  className = '',
  footer,
  width,
  open,
  onCancel,
  onOk,
  okText = '保存',
  cancelText = '取消',
  confirmLoading,
  okButtonProps,
  cancelButtonProps,
  afterOpenChange,
  afterClose,
  destroyOnHidden = true,
  forceRender,
  keyboard = true,
  maskClosable = false,
  closable = true,
  closeIcon,
  getContainer,
  zIndex,
  loading,
  ...modalProps
}: FormSurfaceProps) {
  const heading = useRef<HTMLHeadingElement>(null)
  const headingId = useId()
  useLayoutEffect(() => {
    if (mode !== 'workspace' || !open) return
    const previous =
      document.activeElement instanceof HTMLElement
        ? document.activeElement
        : null
    heading.current?.focus({ preventScroll: true })
    heading.current?.scrollIntoView?.({ block: 'start' })
    return () => {
      if (previous?.isConnected) previous.focus({ preventScroll: true })
    }
  }, [mode, open])

  const actions =
    footer !== undefined ? (
      footer
    ) : onOk ? (
      <Space>
        <Button {...cancelButtonProps} onClick={onCancel}>
          {cancelText}
        </Button>
        <Button
          type="primary"
          loading={confirmLoading}
          {...okButtonProps}
          onClick={onOk}
        >
          {okText}
        </Button>
      </Space>
    ) : null
  const caption = (
    <div className="form-surface-heading">
      <span>{title}</span>
      {description && <p>{description}</p>}
    </div>
  )

  if (mode === 'workspace') {
    if (!open) return null
    return (
      <section
        className={`form-workspace form-surface ${className}`}
        aria-labelledby={headingId}
      >
        <header className="form-workspace-header">
          <Button
            icon={<ArrowLeftOutlined />}
            onClick={onCancel}
            disabled={confirmLoading}
          >
            返回
          </Button>
          <div>
            <h1 id={headingId} ref={heading} tabIndex={-1}>
              {title}
            </h1>
            {description && <p>{description}</p>}
          </div>
        </header>
        <div className="form-workspace-body">{children}</div>
        {actions && (
          <footer className="form-workspace-footer">
            <span className="form-surface-required-note">
              标有 * 的为必填项
            </span>
            <div>{actions}</div>
          </footer>
        )}
      </section>
    )
  }

  if (mode === 'drawer') {
    return (
      <Drawer
        className={`form-surface form-surface-drawer ${className}`}
        rootClassName="form-surface-drawer-root"
        title={caption}
        open={open}
        width={
          typeof width === 'number'
            ? `min(${width}px, 100vw)`
            : (width ?? 'min(680px, 100vw)')
        }
        onClose={(event) =>
          onCancel?.(event as React.MouseEvent<HTMLButtonElement>)
        }
        footer={actions}
        destroyOnHidden={destroyOnHidden}
        forceRender={forceRender}
        keyboard={keyboard}
        maskClosable={maskClosable}
        closable={closable}
        closeIcon={closeIcon}
        getContainer={getContainer}
        zIndex={zIndex}
        loading={loading}
        push={false}
        afterOpenChange={(visible) => {
          afterOpenChange?.(visible)
          if (!visible) afterClose?.()
        }}
      >
        {children}
      </Drawer>
    )
  }

  return (
    <GlassModal
      {...modalProps}
      className={className}
      title={caption}
      open={open}
      width={width ?? 520}
      footer={actions}
      onCancel={onCancel}
      onOk={onOk}
      confirmLoading={confirmLoading}
      destroyOnHidden={destroyOnHidden}
      forceRender={forceRender}
      keyboard={keyboard}
      maskClosable={maskClosable}
      closable={closable}
      closeIcon={closeIcon}
      getContainer={getContainer}
      zIndex={zIndex}
      loading={loading}
      afterOpenChange={afterOpenChange}
      afterClose={afterClose}
    >
      {children}
    </GlassModal>
  )
}

export function FormSection({
  title,
  description,
  children,
}: {
  title: string
  description?: string
  children: ReactNode
}) {
  return (
    <section className="form-section">
      <div className="form-section-heading">
        <h2>{title}</h2>
        {description && <p>{description}</p>}
      </div>
      <div className="form-section-fields">{children}</div>
    </section>
  )
}
