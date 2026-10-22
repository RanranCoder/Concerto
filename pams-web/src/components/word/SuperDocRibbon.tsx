import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { Modal } from 'antd'
import {
  UndoOutlined, RedoOutlined, BoldOutlined, ItalicOutlined, UnderlineOutlined,
  StrikethroughOutlined, FormatPainterOutlined, ClearOutlined, AlignLeftOutlined,
  AlignCenterOutlined, AlignRightOutlined, OrderedListOutlined, UnorderedListOutlined,
  PictureOutlined, TableOutlined, LinkOutlined, FileTextOutlined, CommentOutlined,
  CheckOutlined, CloseOutlined, LeftOutlined, RightOutlined, FullscreenOutlined,
  FullscreenExitOutlined, SearchOutlined, LayoutOutlined,
} from '@ant-design/icons'
import type { SuperDoc } from 'superdoc'
import type { BrowserDocumentApi, CommandExecutionResult, CommandId, SelectionCapture, SuperDocUIState } from 'superdoc/ui'

type Tab = 'home' | 'insert' | 'layout' | 'review' | 'view'
type Dialog = 'table' | 'link' | 'comment' | 'page' | 'pageNumber' | null
type Section = Awaited<ReturnType<BrowserDocumentApi['sections']['list']>>['items'][number]
const tabs: { id: Tab; label: string }[] = [
  { id: 'home', label: '开始' }, { id: 'insert', label: '插入' }, { id: 'layout', label: '布局' },
  { id: 'review', label: '审阅' }, { id: 'view', label: '视图' },
]
const chineseFonts = ['宋体', '仿宋', '黑体', '楷体', '微软雅黑', '等线']
const sizes = [['10.5', '五号'], ['12', '小四'], ['14', '四号'], ['16', '三号'], ['18', '小三'], ['22', '二号'], ['24', '小二'], ['26', '一号'], ['36', '小一']]
// The public section API measures page sizes and margins in inches.
const paperSizes = { A4: [210 / 25.4, 297 / 25.4], A3: [297 / 25.4, 420 / 25.4], Letter: [8.5, 11] } as const

function Group({ name, children }: { name: string; children: ReactNode }) {
  return <div className="pams-ribbon-group" role="group" aria-label={name}><div className="pams-ribbon-controls">{children}</div><span className="pams-ribbon-caption">{name}</span></div>
}

/** The ribbon consumes SuperDoc's public controller; tab changes never reload the DOCX. */
export default function SuperDocRibbon({ superdoc, ready, disabled, mode, onModeChange, fullscreen, onFullscreen, onNotice, onBusyChange }: {
  superdoc: SuperDoc | null; ready: boolean; disabled: boolean; mode: 'editing' | 'suggesting'
  onModeChange: (mode: 'editing' | 'suggesting') => void; fullscreen: boolean; onFullscreen: () => void
  onNotice: (text: string) => void
  onBusyChange: (busy: boolean) => void
}) {
  const ui = superdoc?.ui
  const id = useId()
  const [tab, setTab] = useState<Tab>('home')
  const [snapshot, setSnapshot] = useState<SuperDocUIState | null>(null)
  const [busy, setBusy] = useState(false)
  const [dialog, setDialog] = useState<Dialog>(null)
  const [dialogError, setDialogError] = useState('')
  const [rows, setRows] = useState(3), [columns, setColumns] = useState(3)
  const [href, setHref] = useState(''), [linkText, setLinkText] = useState(''), [comment, setComment] = useState('')
  const [numberPosition, setNumberPosition] = useState<'header' | 'footer'>('footer')
  const [numberAlign, setNumberAlign] = useState<'left' | 'center' | 'right'>('center')
  const [sections, setSections] = useState<Section[]>([]), [sectionIndex, setSectionIndex] = useState(0)
  const [paper, setPaper] = useState(''), [orientation, setOrientation] = useState<'portrait' | 'landscape'>('portrait')
  const [margins, setMargins] = useState({ top: 2.54, bottom: 2.54, left: 2.54, right: 2.54 })
  const capture = useRef<SelectionCapture | null>(null)
  const imageInput = useRef<HTMLInputElement>(null)
  const alive = useRef(true)
  useEffect(() => { alive.current = true; return () => { alive.current = false } }, [])
  useEffect(() => {
    if (!ui) { setSnapshot(null); return }
    const source = ui.select(state => state)
    setSnapshot(source.get())
    return source.subscribe(setSnapshot)
  }, [ui])

  // Custom UI owns the pointer/keyboard coordination for SuperDoc's format painter.
  useEffect(() => {
    const canvas = ui?.viewport.getHost()
    if (!canvas || !ui) return
    const down = () => ui.formatPainter.setPointerSelecting(true)
    const up = () => { ui.formatPainter.setPointerSelecting(false); ui.formatPainter.notifyPointerUp() }
    const keyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') ui.formatPainter.cancel()
      if (event.shiftKey && /Arrow|Home|End|Page/.test(event.key)) ui.formatPainter.setKeyboardSelecting(true)
    }
    const keyUp = () => { ui.formatPainter.setKeyboardSelecting(false); ui.formatPainter.notifyKeyUp() }
    canvas.addEventListener('pointerdown', down)
    canvas.addEventListener('pointerup', up)
    canvas.addEventListener('keydown', keyDown)
    canvas.addEventListener('keyup', keyUp)
    return () => {
      ui.formatPainter.cancel()
      canvas.removeEventListener('pointerdown', down); canvas.removeEventListener('pointerup', up)
      canvas.removeEventListener('keydown', keyDown); canvas.removeEventListener('keyup', keyUp)
    }
  }, [ui, ready])

  const blocked = !ready || disabled || busy
  const state = (command: CommandId) => snapshot?.toolbar.commands[command] ?? ui?.commands.get(command).getState()
  const remember = () => {
    const next = ui?.selection.capture()
    if (next?.selectionTarget || next?.target) capture.current = next
  }
  const restore = () => {
    if (capture.current && ui && !ui.selection.restore(capture.current).success) throw new Error('原来的选区已改变，请重新选择正文后操作。')
  }
  const failure = (error: unknown) => {
    const text = error instanceof Error ? error.message : '此操作未完成，请重新选择正文后重试。'
    if (alive.current) { setDialogError(text); onNotice(text) }
  }
  const check = (result: CommandExecutionResult) => {
    if (result === false) throw new Error('当前选区无法执行此操作，请在正文中重新选择后重试。')
    if (typeof result === 'object' && !result.success && result.failure.code !== 'NO_OP') {
      if (result.failure.message === 'hyperlink-tracked-or-comment-unsupported') throw new Error('当前段落包含批注或修订，暂不能插入链接。请换到其他段落后重试。')
      throw new Error('此操作未完成，请检查选区或文档权限后重试。')
    }
  }
  const run = async (action: () => Promise<unknown> | unknown) => {
    if (blocked || !ui) return false
    setBusy(true); onBusyChange(true); setDialogError(''); onNotice('')
    try { await action(); return true } catch (error) { failure(error); return false }
    finally { if (alive.current) { setBusy(false); onBusyChange(false) } }
  }
  const execute = (command: CommandId, payload?: unknown) => run(async () => {
    if (!['undo', 'redo', 'zoom', 'zoom-fit-width', 'measurement-unit', 'ruler', 'formatting-marks'].includes(command)) restore()
    check(await ui!.commands.executeAsync(command, payload))
  })
  const button = (label: string, icon: ReactNode, command: CommandId, payload?: unknown, toggle = false) => {
    const current = state(command)
    return <button type="button" className="pams-ribbon-button" key={`${command}-${label}`} title={label}
      aria-label={label} aria-pressed={toggle ? !!current?.active : undefined}
      disabled={blocked || !current?.enabled} onMouseDown={event => event.preventDefault()}
      onClick={() => void execute(command, payload)}>{icon}<span>{label}</span></button>
  }
  const openDialog = (next: Dialog) => { setDialogError(''); setDialog(next) }
  const openPage = () => void run(async () => {
    const doc = superdoc?.activeEditor?.doc
    if (!doc) throw new Error('文档尚未准备好')
    const list = await doc.sections.list({ limit: 1000 })
    if (!list.items.length) throw new Error('未能读取页面设置，请重新打开文档。')
    setSections(list.items); loadSection(list.items[0], 0); openDialog('page')
  })
  const loadSection = (section: Section, index: number) => {
    setSectionIndex(index)
    const setup = section.pageSetup
    setOrientation(setup?.orientation ?? 'portrait')
    setPaper(Object.entries(paperSizes).find(([, size]) => {
      const actual = [setup?.width ?? 0, setup?.height ?? 0].sort((a, b) => a - b)
      return Math.abs(actual[0] - size[0]) < 0.01 && Math.abs(actual[1] - size[1]) < 0.01
    })?.[0] ?? '')
    const cm = (value?: number) => Math.round((value ?? 1) * 2.54 * 100) / 100
    setMargins({ top: cm(section.margins?.top), bottom: cm(section.margins?.bottom), left: cm(section.margins?.left), right: cm(section.margins?.right) })
  }
  const applyDialog = () => void run(async () => {
    restore()
    if (dialog === 'table') {
      if (!Number.isInteger(rows) || !Number.isInteger(columns) || rows < 1 || rows > 50 || columns < 1 || columns > 20) throw new Error('表格需为 1–50 行、1–20 列。')
      check(await ui!.commands.executeAsync('table-insert', { rows, columns }))
    } else if (dialog === 'link') {
      if (!/^(https?:\/\/|mailto:|tel:|#)/i.test(href.trim())) throw new Error('请输入以 https://、http://、mailto:、tel: 或 # 开头的链接。')
      check(await ui!.commands.executeAsync('link', { href: href.trim(), text: linkText || undefined }))
    } else if (dialog === 'comment') {
      if (!comment.trim()) throw new Error('请填写批注内容。')
      if (!capture.current) throw new Error('请先选中需要批注的文字。')
      check(await ui!.comments.createFromCapture(capture.current, { text: comment.trim() }))
    } else if (dialog === 'pageNumber') {
      check(await ui!.commands.executeAsync('page-numbers-apply', { kind: numberPosition, alignment: numberAlign }))
    } else if (dialog === 'page') {
      const doc = superdoc?.activeEditor?.doc, section = sections[sectionIndex]
      if (!doc || !section) throw new Error('未能读取当前节，请重试。')
      if (Object.values(margins).some(value => !Number.isFinite(value) || value < 0 || value > 15)) throw new Error('页边距需在 0–15 厘米之间。')
      const size = paperSizes[paper as keyof typeof paperSizes]
      const dimensions = size ?? [section.pageSetup?.width, section.pageSetup?.height].filter((n): n is number => typeof n === 'number').sort((a, b) => a - b)
      const width = dimensions[orientation === 'landscape' ? 1 : 0]
      const height = dimensions[orientation === 'landscape' ? 0 : 1]
      if (width && (margins.left + margins.right) / 2.54 >= width || height && (margins.top + margins.bottom) / 2.54 >= height) throw new Error('页边距之和必须小于纸张尺寸。')
      if (size || orientation !== (section.pageSetup?.orientation ?? 'portrait')) {
        check(await doc.sections.setPageSetup({ target: section.address, width, height, orientation }))
      }
      const nextMargins = Object.fromEntries(Object.entries(margins).map(([key, value]) => {
        const original = section.margins?.[key as keyof typeof margins]
        return [key, original !== undefined && Math.round(original * 2.54 * 100) / 100 === value ? original : value / 2.54]
      }))
      check(await doc.sections.setPageMargins({ target: section.address, ...nextMargins }))
    }
    if (alive.current) setDialog(null)
  })
  const pickImage = async (file?: File) => {
    if (!file) return
    await run(async () => {
      if (!/image\/(png|jpeg)/.test(file.type) && !/\.(png|jpe?g)$/i.test(file.name)) throw new Error('请选择 PNG 或 JPG 图片。')
      if (file.size > 10 * 1024 * 1024) throw new Error('请选择不超过 10MB 的图片。')
      const src = await new Promise<string>((resolve, reject) => {
        const reader = new FileReader()
        reader.onload = () => resolve(String(reader.result).replace(/^data:[^;]*;/, `data:${/\.png$/i.test(file.name) ? 'image/png' : 'image/jpeg'};`))
        reader.onerror = () => reject(new Error('图片读取失败，请重新选择。'))
        reader.readAsDataURL(file)
      })
      restore(); check(await ui!.commands.executeAsync('image', { src, alt: file.name }))
    })
  }
  const navigate = (direction: 'next' | 'previous') => void run(async () => {
    const result = await (direction === 'next' ? ui!.trackChanges.navigateNext() : ui!.trackChanges.navigatePrevious())
    if (!result.success) throw new Error('未能定位这处修订，请重试。')
    capture.current = ui!.selection.capture()
  })
  const families = [...new Set([...chineseFonts, ...(snapshot?.fonts.options.map(option => option.value) ?? [])])]
  const valueOf = (command: CommandId) => typeof state(command)?.value === 'string' || typeof state(command)?.value === 'number' ? String(state(command)!.value) : ''
  const fontValue = valueOf('font-family')
  if (fontValue && !families.includes(fontValue)) families.unshift(fontValue)
  const fontSize = valueOf('font-size')
  const fontSizes = [...new Set(['9', '10.5', '11', '12', '14', '16', '18', '20', '22', '24', '26', '28', '36', '48', '72', ...(fontSize ? [fontSize] : [])])]
  const styles = snapshot?.styles.quickGallery ?? []
  const dialogTitle = { table: '插入表格', link: '插入链接', comment: '添加批注', page: '页面设置', pageNumber: '设置页码' }
  return <>
    <div className="pams-superdoc-toolbar" data-ribbon-tab={tab} onPointerDownCapture={remember} onFocusCapture={remember}>
      <div className="pams-ribbon-tabs" role="tablist" aria-label="文档工具">
        {tabs.map((item, index) => <button key={item.id} type="button" role="tab" id={`${id}-${item.id}`} aria-selected={tab === item.id}
          aria-controls={`${id}-panel`} tabIndex={tab === item.id ? 0 : -1} onMouseDown={event => event.preventDefault()}
          onClick={() => setTab(item.id)} onKeyDown={event => {
            if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
            event.preventDefault()
            const next = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length
            setTab(tabs[next].id); event.currentTarget.parentElement?.querySelectorAll<HTMLButtonElement>('[role=tab]')[next].focus()
          }}>{item.label}</button>)}
        <span className={`pams-ribbon-mode ${mode === 'suggesting' ? 'is-suggesting' : ''}`}>{mode === 'suggesting' ? '修订已开启' : '直接编辑'}</span>
      </div>
      <div className="pams-ribbon-panel" role="tabpanel" id={`${id}-panel`} aria-labelledby={`${id}-${tab}`}>
        {tab === 'home' && <>
          <Group name="编辑">{button('撤销', <UndoOutlined />, 'undo')}{button('重做', <RedoOutlined />, 'redo')}
            {button('格式刷', <FormatPainterOutlined />, 'copy-format', undefined, true)}{button('清除格式', <ClearOutlined />, 'clear-formatting')}</Group>
          <Group name="字体"><div className="pams-ribbon-row">
            <select aria-label="字体" disabled={blocked || !state('font-family')?.enabled} value={fontValue} onChange={event => void execute('font-family', event.target.value)}>
              <option value="" disabled>选择字体</option>{families.map(font => <option key={font} value={font}>{font}</option>)}</select>
            <select className="pams-ribbon-size" aria-label="字号" disabled={blocked || !state('font-size')?.enabled} value={fontSize} onChange={event => void execute('font-size', event.target.value)}>
              <option value="" disabled>字号</option>{fontSizes.map(size => <option key={size} value={size}>{sizes.find(item => item[0] === size)?.[1] ?? size} · {size}</option>)}</select></div>
            <div className="pams-ribbon-row pams-ribbon-compact">{button('加粗', <BoldOutlined />, 'bold', undefined, true)}{button('斜体', <ItalicOutlined />, 'italic', undefined, true)}{button('下划线', <UnderlineOutlined />, 'underline', undefined, true)}{button('删除线', <StrikethroughOutlined />, 'strikethrough', undefined, true)}
              <label className="pams-ribbon-color" title="文字颜色">A<input type="color" aria-label="文字颜色" disabled={blocked || !state('text-color')?.enabled} defaultValue="#000000" onChange={event => void execute('text-color', event.target.value)} /></label>
              <label className="pams-ribbon-color is-highlight" title="文字高亮">A<input type="color" aria-label="文字高亮" disabled={blocked || !state('highlight-color')?.enabled} defaultValue="#fff176" onChange={event => void execute('highlight-color', event.target.value)} /></label>
            </div></Group>
          <Group name="段落"><div className="pams-ribbon-row pams-ribbon-compact">{button('左对齐', <AlignLeftOutlined />, 'text-align', 'left')}{button('居中对齐', <AlignCenterOutlined />, 'text-align', 'center')}{button('右对齐', <AlignRightOutlined />, 'text-align', 'right')}{button('两端对齐', <span>☰</span>, 'text-align', 'justify')}{button('项目符号', <UnorderedListOutlined />, 'bullet-list', undefined, true)}{button('编号列表', <OrderedListOutlined />, 'numbered-list', undefined, true)}</div>
            <div className="pams-ribbon-row">{button('减少缩进', <span>⇤</span>, 'indent-decrease')}{button('增加缩进', <span>⇥</span>, 'indent-increase')}
              <select aria-label="行距" defaultValue="" disabled={blocked || !state('line-height')?.enabled} onChange={event => void execute('line-height', Number(event.target.value))}>
                <option value="" disabled>行距</option>{[1, 1.15, 1.5, 2, 2.5, 3].map(line => <option key={line} value={line}>{line} 倍</option>)}</select></div></Group>
          <Group name="样式"><select aria-label="标题样式" value={snapshot?.styles.activeParagraphStyleId ?? ''} disabled={blocked || !state('linked-style')?.enabled} onChange={event => void execute('linked-style', event.target.value)}>
            <option value="" disabled>选择样式</option>{styles.map(style => <option key={style.id} value={style.id}>{/^normal$/i.test(style.name) ? '正文' : style.name.replace(/^heading\s*(\d+)$/i, '标题 $1').replace(/^title$/i, '标题').replace(/^subtitle$/i, '副标题')}</option>)}</select></Group>
          <Group name="查找"><button type="button" aria-label="查找替换" className="pams-ribbon-button" disabled={blocked} onMouseDown={event => event.preventDefault()} onClick={() => { const result = ui?.search.open(); if (result?.ok) superdoc?.emit('search:open'); else onNotice('查找暂不可用，请重试。') }}><SearchOutlined /><span>查找替换</span></button></Group>
        </>}
        {tab === 'insert' && <>
          <Group name="图文"><button type="button" aria-label="图片" className="pams-ribbon-button is-large" disabled={blocked || !state('image')?.enabled} onMouseDown={event => event.preventDefault()} onClick={() => imageInput.current?.click()}><PictureOutlined /><span>图片</span></button>
            <button type="button" aria-label="链接" className="pams-ribbon-button is-large" disabled={blocked || !state('link')?.enabled} onMouseDown={event => event.preventDefault()} onClick={() => { setHref(''); setLinkText(snapshot?.selection.quotedText ?? ''); openDialog('link') }}><LinkOutlined /><span>链接</span></button></Group>
          <Group name="表格"><button type="button" aria-label="插入表格" className="pams-ribbon-button is-large" disabled={blocked || !state('table-insert')?.enabled} onMouseDown={event => event.preventDefault()} onClick={() => openDialog('table')}><TableOutlined /><span>插入表格</span></button>
            <div className="pams-ribbon-stack">{button('下方插入行', <span>＋</span>, 'table-add-row-after')}{button('右侧插入列', <span>＋</span>, 'table-add-column-after')}</div>
            <select aria-label="表格操作" value="" disabled={blocked || !state('table-delete')?.enabled} onChange={event => void execute(event.target.value)}>
              <option value="" disabled>更多表格操作</option>{[['table-add-row-before', '上方插入行'], ['table-add-column-before', '左侧插入列'], ['table-delete-row', '删除行'], ['table-delete-column', '删除列'], ['table-merge-cells', '合并单元格'], ['table-split-cell', '拆分单元格'], ['table-remove-borders', '移除边框'], ['table-delete', '删除表格']].map(([command, label]) => <option key={command} value={command} disabled={!state(command)?.enabled}>{label}</option>)}</select></Group>
          <Group name="文档">{button('目录', <FileTextOutlined />, 'table-of-contents-insert')}
            <button type="button" aria-label="页码" className="pams-ribbon-button" disabled={blocked || !state('page-numbers-apply')?.enabled} onMouseDown={event => event.preventDefault()} onClick={() => openDialog('pageNumber')}><FileTextOutlined /><span>页码</span></button></Group>
        </>}
        {tab === 'layout' && <>
          <Group name="页面"><button type="button" aria-label="页面设置" className="pams-ribbon-button is-large" disabled={blocked || mode === 'suggesting'} onMouseDown={event => event.preventDefault()} onClick={openPage}><LayoutOutlined /><span>页面设置</span></button><span className="pams-ribbon-help">纸张、方向与页边距</span></Group>
          <Group name="测量">{button('显示标尺', <span>↔</span>, 'ruler', undefined, true)}<select aria-label="测量单位" disabled={blocked} value={valueOf('measurement-unit') || 'cm'} onChange={event => void execute('measurement-unit', event.target.value)}><option value="cm">厘米</option><option value="in">英寸</option></select></Group>
          <p className="pams-ribbon-help">{mode === 'suggesting' ? '页面设置请先关闭修订。' : '页面设置可按节调整；标尺可拖动左右页边距。'}</p>
        </>}
        {tab === 'review' && <>
          <Group name="批注"><button type="button" aria-label="添加批注" className="pams-ribbon-button is-large" disabled={blocked || !snapshot?.selection.quotedText} onMouseDown={event => event.preventDefault()} onClick={() => { setComment(''); openDialog('comment') }}><CommentOutlined /><span>添加批注</span></button></Group>
          <Group name="修订"><button type="button" aria-label={mode === 'suggesting' ? '关闭修订' : '开启修订'} className="pams-ribbon-button is-large" aria-pressed={mode === 'suggesting'} disabled={blocked} onMouseDown={event => event.preventDefault()} onClick={() => { onModeChange(mode === 'suggesting' ? 'editing' : 'suggesting'); onNotice('') }}><FileTextOutlined /><span>{mode === 'suggesting' ? '关闭修订' : '开启修订'}</span></button><span className="pams-ribbon-help">{snapshot?.trackChanges.total ?? 0} 处待处理</span></Group>
          <Group name="定位修改"><button type="button" aria-label="上一处修改" className="pams-ribbon-button" disabled={blocked || !snapshot?.trackChanges.total} onMouseDown={event => event.preventDefault()} onClick={() => navigate('previous')}><LeftOutlined /><span>上一处修改</span></button><button type="button" aria-label="下一处修改" className="pams-ribbon-button" disabled={blocked || !snapshot?.trackChanges.total} onMouseDown={event => event.preventDefault()} onClick={() => navigate('next')}><RightOutlined /><span>下一处修改</span></button></Group>
          <Group name="处理修改">{button('接受修改', <CheckOutlined />, 'acceptChange')}{button('拒绝修改', <CloseOutlined />, 'rejectChange')}</Group>
          <p className="pams-ribbon-help">文档修订保存到 Word 文件，审核结果在右侧处理。</p>
        </>}
        {tab === 'view' && <>
          <Group name="缩放"><select aria-label="文档缩放" value={snapshot?.zoom.mode === 'fit-width' ? 'fit' : String(snapshot?.zoom.value ?? 100)} disabled={blocked} onChange={event => void execute(event.target.value === 'fit' ? 'zoom-fit-width' : 'zoom', event.target.value === 'fit' ? undefined : Number(event.target.value))}>
            <option value="fit">适应宽度</option>{[...new Set([50, 75, 100, 125, 150, 200, snapshot?.zoom.value ?? 100])].sort((a, b) => a - b).map(zoom => <option key={zoom} value={zoom}>{zoom}%</option>)}</select>
            {button('适应宽度', <span>↔</span>, 'zoom-fit-width')}</Group>
          <Group name="显示">{button('显示标尺', <span>↔</span>, 'ruler', undefined, true)}{button('段落标记', <span>¶</span>, 'formatting-marks', undefined, true)}</Group>
          <Group name="工作区"><button type="button" aria-label={fullscreen ? '退出全屏' : '全屏编辑'} className="pams-ribbon-button is-large" disabled={disabled} onMouseDown={event => event.preventDefault()} onClick={onFullscreen}>{fullscreen ? <FullscreenExitOutlined /> : <FullscreenOutlined />}<span>{fullscreen ? '退出全屏' : '全屏编辑'}</span></button></Group>
        </>}
      </div>
      <input ref={imageInput} type="file" aria-label="选择图片" accept=".png,.jpg,.jpeg,image/png,image/jpeg" hidden onChange={event => { void pickImage(event.target.files?.[0]); event.target.value = '' }} />
    </div>
    <Modal title={dialog ? dialogTitle[dialog] : ''} open={!!dialog} getContainer={false} centered width={480} focusTriggerAfterClose={false}
      afterClose={() => superdoc?.activeEditor?.focus?.({ preventScroll: true, restoreSelection: true })}
      onCancel={() => { if (!busy) setDialog(null) }} onOk={applyDialog} okText={dialog === 'page' ? '应用设置' : '确定'} cancelText="取消" confirmLoading={busy}
      okButtonProps={{ disabled: disabled || !ready }} cancelButtonProps={{ disabled: busy }} closable={!busy} maskClosable={!busy} keyboard={!busy}>
      <div className="pams-ribbon-form">
        {dialog === 'table' && <><label>行数<input aria-label="表格行数" type="number" min={1} max={50} value={rows} onChange={event => setRows(Number(event.target.value))} /></label><label>列数<input aria-label="表格列数" type="number" min={1} max={20} value={columns} onChange={event => setColumns(Number(event.target.value))} /></label></>}
        {dialog === 'link' && <><label>显示文字<input aria-label="链接文字" value={linkText} onChange={event => setLinkText(event.target.value)} /></label><label>链接地址<input aria-label="链接地址" type="url" placeholder="https://" value={href} onChange={event => setHref(event.target.value)} /></label></>}
        {dialog === 'comment' && <><blockquote>{capture.current?.quotedText}</blockquote><label>批注内容<textarea aria-label="批注内容" rows={4} value={comment} onChange={event => setComment(event.target.value)} /></label></>}
        {dialog === 'pageNumber' && <><label>位置<select aria-label="页码位置" value={numberPosition} onChange={event => setNumberPosition(event.target.value as 'header' | 'footer')}><option value="footer">页脚</option><option value="header">页眉</option></select></label><label>对齐<select aria-label="页码对齐" value={numberAlign} onChange={event => setNumberAlign(event.target.value as typeof numberAlign)}><option value="left">左侧</option><option value="center">居中</option><option value="right">右侧</option></select></label><p>应用到当前节的页眉或页脚，已有内容会保留。</p></>}
        {dialog === 'page' && <><label>应用到<select aria-label="设置范围" value={sectionIndex} onChange={event => loadSection(sections[Number(event.target.value)], Number(event.target.value))}>{sections.map((section, index) => <option key={section.address.sectionId} value={index}>第 {index + 1} 节</option>)}</select></label>
          <div className="pams-ribbon-form-row"><label>纸张<select aria-label="纸张大小" value={paper} onChange={event => setPaper(event.target.value)}><option value="">保留原始尺寸</option>{Object.keys(paperSizes).map(size => <option key={size} value={size}>{size}</option>)}</select></label><label>方向<select aria-label="纸张方向" value={orientation} onChange={event => setOrientation(event.target.value as typeof orientation)}><option value="portrait">纵向</option><option value="landscape">横向</option></select></label></div>
          <p>页边距（厘米）</p><div className="pams-ribbon-form-row">{(['top', 'bottom', 'left', 'right'] as const).map((key, index) => <label key={key}>{['上', '下', '左', '右'][index]}<input aria-label={`${['上', '下', '左', '右'][index]}页边距`} type="number" min={0} max={15} step={0.1} value={margins[key]} onChange={event => setMargins(previous => ({ ...previous, [key]: Number(event.target.value) }))} /></label>)}</div></>}
        {dialogError && <p role="alert" className="pams-ribbon-error">{dialogError}</p>}
      </div>
    </Modal>
  </>
}
