// @vitest-environment jsdom
import { beforeEach, afterEach, describe, expect, it, vi } from 'vitest'
import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
  within,
} from '@testing-library/react'
import { App } from 'antd'
import type { ActivityDetail, ActivityPlanVO } from '@/api/activity'
import ActivityPlanTab from './ActivityPlanTab'
import * as api from '@/api/plan'
import { Blob as NodeBlob, File as NodeFile } from 'node:buffer'
import { planToDocx } from '@/components/word/legacyPlanMigration'
import { uploadFile, fetchFileBlob } from '@/api/file'

vi.mock('@/api/plan', () => ({
  createPlan: vi.fn(),
  fetchPlanDocument: vi.fn(),
  updatePlan: vi.fn(),
  getPlan: vi.fn(),
  planWorkspace: vi.fn(),
  planReviewers: vi.fn(),
  submitPlan: vi.fn(),
  reviewPlan: vi.fn(),
  revisePlan: vi.fn(),
}))
vi.mock('@/stores/auth', () => ({
  useAuthStore: (select: (s: unknown) => unknown) =>
    select({ user: { id: 1 } }),
}))
vi.mock('@/components/glass/RequireRole', () => ({
  useHasPerm: () => () => true,
}))
vi.mock('@/components/word/SuperDocDocument', async () => {
  const React = await import('react')
  return { default: React.forwardRef(function MockDocument(
    { document, editing, onChange, onReady }: { document: Blob; editing: boolean; onChange?: () => void; onReady?: (v: boolean) => void }, ref,
  ) {
    const [text, setText] = React.useState('')
    React.useEffect(() => { void document.text().then(v => { setText(v); onReady?.(true) }) }, [document])
    React.useImperativeHandle(ref, () => ({ exportDocx: async () => new Blob([text]) }), [text])
    return editing ? <textarea aria-label="文档正文" value={text} onChange={e => { setText(e.target.value); onChange?.() }} /> : <div>已保存的文档预览</div>
  }) }
})
vi.mock('@/api/file', () => ({ uploadFile: vi.fn(), fetchFileBlob: vi.fn() }))
vi.mock('@/components/word/legacyPlanMigration', () => ({
  planToDocx: vi.fn(async (fields: { content?: string }) => new Blob([fields.content || '新建文档'])),
  parseSectionOrder: () => [],
}))
vi.mock('@/components/form/FormSurface', () => ({
  default: ({
    open,
    title,
    children,
    footer,
  }: {
    open: boolean
    title: string
    children: React.ReactNode
    footer: React.ReactNode
  }) =>
    open ? (
      <section role="dialog" aria-label={title}>
        {children}
        {footer}
      </section>
    ) : null,
}))

const saved = {
  id: 11,
  activityId: 7,
  version: 1,
  status: 'DRAFT',
  content: '已保存的正文',
  ownerId: 1,
  reviewers: [],
  updatedAt: '2026-10-21T10:00:00',
} as unknown as ActivityPlanVO
const workspace = (doc = saved): NonNullable<ActivityDetail['plan']> => ({
  latest: doc,
  status: doc.status,
  currentHidden: false,
  currentId: doc.id,
  canEdit: true,
  versions: [doc],
})
function mount(plan = workspace()) {
  return render(
    <App>
      <ActivityPlanTab
        activityId={7}
        activity={
          {
            id: 7,
            name: '活动策划',
            theme: '',
            organizer: '组织部',
          } as ActivityDetail['activity']
        }
        plan={plan}
        onChanged={vi.fn()}
      />
    </App>,
  )
}
beforeEach(() => {
  vi.clearAllMocks()
  vi.stubGlobal('Blob', NodeBlob)
  vi.stubGlobal('File', NodeFile)
  vi.mocked(uploadFile).mockResolvedValue({ id: 99 } as Awaited<ReturnType<typeof uploadFile>>)
  vi.mocked(fetchFileBlob).mockResolvedValue(new Blob(['已保存的原生文档']))
  vi.mocked(api.fetchPlanDocument).mockResolvedValue(new Blob(['已保存的原生文档']))
  vi.stubGlobal('matchMedia', () => ({
    matches: false,
    addListener: () => {},
    removeListener: () => {},
    addEventListener: () => {},
    removeEventListener: () => {},
  }))
  vi.mocked(api.planWorkspace).mockResolvedValue(workspace())
})
afterEach(() => cleanup())

async function edit() {
  const button = screen.getByRole('button', { name: /编辑策划/ })
  await waitFor(() => expect(button.hasAttribute('disabled')).toBe(false))
  fireEvent.click(button)
  await screen.findByRole('textbox', { name: '文档正文' })
  await waitFor(() => expect(screen.getByRole('button', { name: '保存策划书' }).hasAttribute('disabled')).toBe(false))
}

describe('策划书文档工作区', () => {
  it('原生版本直接获取 DOCX，不再走 HTML 重建', async () => {
    mount(workspace({ ...saved, documentFileId: 88, documentText: '原生正文' }))
    await waitFor(() => expect(api.fetchPlanDocument).toHaveBeenCalledWith(11))
    expect(planToDocx).not.toHaveBeenCalled()
    await edit()
    expect((screen.getByRole('textbox', { name: '文档正文' }) as HTMLTextAreaElement).value).toBe('已保存的原生文档')
  })
  it('常驻侧栏展示时间线，选择历史版后对比已保存的章节', async () => {
    const old = {
      ...saved,
      id: 10,
      version: 1,
      status: 'REJECTED' as const,
      purpose: '<p>旧目标</p>',
    }
    const current = { ...saved, version: 2, purpose: '<p>新目标</p>' }
    vi.mocked(api.getPlan).mockResolvedValue(old)
    mount({
      ...workspace(current),
      versions: [
        { ...current, ownerName: '作者甲', note: '当前工作版本' },
        { ...old, ownerName: '作者甲', note: '请完善目标' },
      ],
    })
    const sidebar = within(
      screen.getByRole('complementary', { name: '策划书审核与版本侧栏' }),
    )
    fireEvent.click(sidebar.getByRole('tab', { name: '版本记录' }))
    expect(sidebar.getByText('每次提交，都有记录')).toBeTruthy()
    fireEvent.click(sidebar.getByRole('button', { name: /v1/ }))
    await waitFor(() => expect(api.getPlan).toHaveBeenCalledWith(10))
    const diff = await sidebar.findByRole('region', { name: '版本章节对比' })
    expect(within(diff).getByText('旧目标')).toBeTruthy()
    expect(within(diff).getByText('新目标')).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()
  })
  it('当前版不可见时不提供章节对比，也不请求当前版正文', async () => {
    mount({
      ...workspace(),
      currentId: null,
      currentHidden: true,
      canEdit: false,
    })
    expect(screen.queryByRole('region', { name: '版本章节对比' })).toBeNull()
    expect(
      screen.getByText('您无权查看当前版本，因此不会展示与当前版的对比。'),
    ).toBeTruthy()
    expect(api.getPlan).not.toHaveBeenCalled()
  })
  it('历史文档封面和落款使用版本快照中的名称与组织', async () => {
    const doc = {
      ...saved,
      nameOverride: '历史活动名称',
      organizerOverride: '历史组织',
      timeOverride: '2025-10-21',
    }
    mount({
      ...workspace(doc),
      currentId: null,
      currentHidden: true,
      canEdit: false,
    })
    await waitFor(() =>
      expect(planToDocx).toHaveBeenCalledWith(
        expect.anything(),
        expect.objectContaining({
          name: '历史活动名称',
          orgName: '历史组织',
          endDate: '2025-10-21',
        }),
        expect.anything(),
        expect.anything(),
      ),
    )
  })
  it('在文档内编辑，取消后丢弃输入并隐藏编辑栏', async () => {
    mount()
    await edit()
    expect(screen.queryByRole('dialog')).toBeNull()
    fireEvent.change(screen.getByRole('textbox', { name: '文档正文' }), {
      target: { value: '未保存正文' },
    })
    fireEvent.click(screen.getByRole('button', { name: '取消编辑' }))
    fireEvent.click(await screen.findByRole('button', { name: '放弃修改' }))
    await waitFor(() => expect(screen.queryByText('正在编辑策划书')).toBeNull())
    await edit()
    expect(
      (screen.getByRole('textbox', { name: '文档正文' }) as HTMLTextAreaElement)
        .value,
    ).toBe('已保存的正文')
    expect(api.updatePlan).not.toHaveBeenCalled()
  })
  it('保存后刷新原处文档，再次编辑得到保存后的内容', async () => {
    vi.mocked(api.planWorkspace).mockResolvedValue(
      workspace({ ...saved, content: '保存后的内容' }),
    )
    mount()
    await edit()
    fireEvent.change(screen.getByRole('textbox', { name: '文档正文' }), {
      target: { value: '保存后的内容' },
    })
    fireEvent.click(screen.getByRole('button', { name: '保存策划书' }))
    await waitFor(() =>
      expect(api.updatePlan).toHaveBeenCalledWith(
        11,
        expect.objectContaining({ activityId: 7, documentFileId: 99 }),
      ),
    )
    await waitFor(() => expect(screen.queryByText('正在编辑策划书')).toBeNull())
    await edit()
    expect(
      (screen.getByRole('textbox', { name: '文档正文' }) as HTMLTextAreaElement)
        .value,
    ).toBe('保存后的内容')
  })
  it('保存失败保留未保存内容和编辑栏', async () => {
    vi.mocked(api.updatePlan).mockRejectedValueOnce(new Error('save failed'))
    mount()
    await edit()
    fireEvent.change(screen.getByRole('textbox', { name: '文档正文' }), {
      target: { value: '保留输入' },
    })
    fireEvent.click(screen.getByRole('button', { name: '保存策划书' }))
    await waitFor(() => expect(api.updatePlan).toHaveBeenCalled())
    expect(
      (screen.getByRole('textbox', { name: '文档正文' }) as HTMLTextAreaElement)
        .value,
    ).toBe('保留输入')
  })
  it('勾选多个审核人后准确提交 ID 列表', async () => {
    vi.mocked(api.planReviewers).mockResolvedValue([
      { id: 2, name: '审核甲', deptName: '组织部', roleName: '部长' },
      { id: 3, name: '审核乙', deptName: '宣传部', roleName: '部长' },
    ])
    mount()
    fireEvent.click(screen.getByRole('button', { name: '提交审核' }))
    expect(
      screen
        .getByRole('button', { name: '提交给 0 位审核人' })
        .hasAttribute('disabled'),
    ).toBe(true)
    fireEvent.click(await screen.findByRole('checkbox', { name: /审核甲/ }))
    fireEvent.click(screen.getByRole('checkbox', { name: /审核乙/ }))
    fireEvent.click(screen.getByRole('button', { name: '提交给 2 位审核人' }))
    await waitFor(() => expect(api.submitPlan).toHaveBeenCalledWith(11, [2, 3]))
  })
  it('未获当前版权限时仅提供历史阅读入口', () => {
    mount({
      ...workspace(),
      currentHidden: true,
      currentId: null,
      canEdit: false,
    })
    expect(screen.getByText(/当前版本仅作者与所选审核人可见。/)).toBeTruthy()
    expect(screen.queryByRole('button', { name: '编辑策划' })).toBeNull()
    expect(screen.queryByRole('button', { name: '提交审核' })).toBeNull()
  })
})
