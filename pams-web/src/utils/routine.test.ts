import { describe, expect, it } from 'vitest'
import dayjs from 'dayjs'
import type { ScheduleVO } from '@/api/schedule'
import {
  FALLBACK_PERIODS,
  freeInWeek,
  inCalendarWeek,
  monday,
  periodFor,
} from './routine'

describe('routine calendar and availability', () => {
  it('keeps explicit lesson ranges separate from clock times', () => {
    expect(
      periodFor({ sessionName: '下午第5-6节' } as ScheduleVO, FALLBACK_PERIODS),
    ).toBe(2)
    expect(
      periodFor({ sessionName: '09:00-09:10' } as ScheduleVO, FALLBACK_PERIODS),
    ).toBe(-1)
  })
  it('places activity shifts by real configured time even without a lesson name', () => {
    const periods = [
      { ...FALLBACK_PERIODS[0], startTime: '08:00:00', endTime: '09:40:00' },
    ]
    expect(
      periodFor(
        { startTime: '08:30:00', sessionName: null } as ScheduleVO,
        periods,
      ),
    ).toBe(0)
  })
  it('does not merge undated teaching weeks into the current calendar week', () => {
    const start = monday(dayjs('2026-10-14'))
    expect(
      inCalendarWeek({ scheduleDate: '2026-10-18' } as ScheduleVO, start),
    ).toBe(true)
    expect(
      inCalendarWeek({ scheduleDate: '2026-10-19' } as ScheduleVO, start),
    ).toBe(false)
    expect(
      inCalendarWeek({ scheduleDate: null, weekNo: 7 } as ScheduleVO, start),
    ).toBe(false)
    expect(
      inCalendarWeek({ scheduleDate: null, weekNo: 7 } as ScheduleVO, start, 7),
    ).toBe(true)
  })
  it('supports split weeks, parity and full-course zero from imported results', () => {
    expect(freeInWeek('1-4;9-18', 7)).toBe(false)
    expect(freeInWeek('1-4;9-18', 10)).toBe(true)
    expect(freeInWeek('1-19 单', 7)).toBe(true)
    expect(freeInWeek('1-19 单', 8)).toBe(false)
    expect(freeInWeek('2-20 双', 8)).toBe(true)
    expect(freeInWeek('0', 7)).toBe(false)
  })
})
