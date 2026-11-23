// @vitest-environment jsdom
import { App } from 'antd'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { afterEach, expect, it, vi } from 'vitest'
import { getScanConfig, scanSignin } from '@/api/signin'
import SigninScan from './SigninScan'

vi.mock('@/api/signin', () => ({
  getScanConfig: vi.fn(),
  scanSignin: vi.fn(),
}))

afterEach(() => {
  cleanup()
  vi.resetAllMocks()
  vi.unstubAllGlobals()
})

it.each([
  { fields: [], extraLabel: '学号', extraValue: '' },
  {
    fields: [
      { id: 1, activityId: 7, fieldName: '签到序号', fieldKey: 'sequence', required: true, fieldType: 'NUMBER', sortOrder: 0 },
      { id: 2, activityId: 7, fieldName: '姓名', fieldKey: 'name', required: true, fieldType: 'TEXT', sortOrder: 1 },
    ],
    extraLabel: '签到序号',
    extraValue: '0',
  },
])('submits the name and any entered verification fields ($extraLabel)', async ({ fields, extraLabel, extraValue }) => {
  vi.stubGlobal('matchMedia', vi.fn(() => ({
    matches: false,
    addListener: vi.fn(),
    removeListener: vi.fn(),
  })))
  vi.mocked(getScanConfig).mockResolvedValue({ activityId: 7, fields })
  render(
    <MemoryRouter initialEntries={['/signin/activity-token']} future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <Routes>
        <Route path="/signin/:token" element={<App><SigninScan /></App>} />
      </Routes>
    </MemoryRouter>,
  )

  fireEvent.change(await screen.findByLabelText('姓名'), { target: { value: ' 张三 ' } })
  if (extraValue) {
    fireEvent.change(screen.getByLabelText(extraLabel), { target: { value: extraValue } })
  }
  fireEvent.click(screen.getByRole('button', { name: /^签\s*到$/ }))

  await waitFor(() => expect(scanSignin).toHaveBeenCalledWith({
    token: 'activity-token',
    fields: extraValue ? { 姓名: '张三', [extraLabel]: extraValue } : { 姓名: '张三' },
  }))
})
