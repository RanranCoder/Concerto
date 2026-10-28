import { describe, expect, it } from 'vitest'
import dayjs from 'dayjs'
import {
  timelineScale,
  timelineStart,
  timelineTicks,
  wheelDayWidth,
  MIN_DAY_WIDTH,
  MAX_DAY_WIDTH,
  type GanttZoom,
} from './gantt.scale'

const shortProject = {
  tasks: [{ startDate: '2026-10-20', endDate: '2026-10-25' }],
  activityDate: '2026-10-24',
  today: '2026-10-22',
  availableWidth: 1100,
}
describe('gantt time scale', () => {
  it('zooms continuously in both directions and clamps the usable scale', () => {
    expect(wheelDayWidth(42, -120)).toBeGreaterThan(42)
    expect(wheelDayWidth(42, 120)).toBeLessThan(42)
    expect(wheelDayWidth(wheelDayWidth(42, -120), 120)).toBeCloseTo(42)
    expect(wheelDayWidth(MIN_DAY_WIDTH, 120)).toBe(MIN_DAY_WIDTH)
    expect(wheelDayWidth(MAX_DAY_WIDTH, -120)).toBe(MAX_DAY_WIDTH)
    expect(wheelDayWidth(42, -10000)).toBe(wheelDayWidth(42, -240))
  })
  it('uses a custom scale with adaptive calendar ticks and a stable start date', () => {
    for (const [customPixelsPerDay, unit] of [
      [60, 'day'],
      [20, 'week'],
      [2, 'month'],
    ] as const) {
      const layout = timelineScale({
        ...shortProject,
        zoom: 'custom',
        customPixelsPerDay,
        anchor: '2026-10-19',
      })
      expect(layout.start).toBe('2026-10-19')
      expect(layout.pixelsPerDay).toBe(customPixelsPerDay)
      expect(layout.unit).toBe(unit)
      expect(layout.gridWidth).toBeGreaterThanOrEqual(
        shortProject.availableWidth,
      )
    }
  })
  it('keeps short projects visibly different at day, week and month zooms', () => {
    const day = timelineScale({ ...shortProject, zoom: 'day' })
    const week = timelineScale({ ...shortProject, zoom: 'week' })
    const month = timelineScale({ ...shortProject, zoom: 'month' })
    expect([day.pixelsPerDay, week.pixelsPerDay, month.pixelsPerDay]).toEqual([
      42, 16, 5,
    ])
    expect(week.days).toBeGreaterThan(day.days)
    expect(month.days).toBeGreaterThan(week.days)
    for (const layout of [day, week, month])
      expect(layout.gridWidth).toBeGreaterThanOrEqual(
        shortProject.availableWidth,
      )
  })
  it('aligns week columns and navigation to Monday through Sunday', () => {
    const layout = timelineScale({ ...shortProject, zoom: 'week' })
    expect(layout.start).toBe('2026-10-19')
    expect(dayjs(layout.end).day()).toBe(0)
    const ticks = timelineTicks(layout.start, layout.days, layout.unit)
    expect(ticks[0]).toMatchObject({
      label: '10/19 — 10/25',
      days: 7,
      offset: 0,
    })
    expect(ticks.every((t) => t.days === 7)).toBe(true)
  })
  it('uses actual calendar month lengths, including leap February', () => {
    const layout = timelineScale({
      ...shortProject,
      zoom: 'month',
      availableWidth: 1,
      activityDate: null,
      tasks: [{ startDate: '2028-02-12', endDate: '2028-03-04' }],
    })
    expect(layout.start).toBe('2028-02-01')
    expect(layout.end).toBe('2028-03-31')
    expect(
      timelineTicks(layout.start, layout.days, layout.unit).map((t) => [
        t.label,
        t.days,
      ]),
    ).toEqual([
      ['2月', 29],
      ['3月', 31],
    ])
  })
  it('fits the full task and activity range even after navigating elsewhere', () => {
    for (const activityDate of ['2026-09-01', '2026-12-31']) {
      const layout = timelineScale({
        ...shortProject,
        activityDate,
        zoom: 'fit',
        anchor: '2027-02-01',
      })
      expect(layout.start <= activityDate && layout.end >= activityDate).toBe(
        true,
      )
      expect(layout.start <= '2026-10-20' && layout.end >= '2026-10-25').toBe(
        true,
      )
      expect(layout.gridWidth).toBe(shortProject.availableWidth)
    }
  })
  it('does not truncate long plans at the former 60/120/180 day limits', () => {
    for (const zoom of ['day', 'week', 'month', 'fit'] as GanttZoom[]) {
      const layout = timelineScale({
        ...shortProject,
        zoom,
        tasks: [{ startDate: '2026-01-01', endDate: '2027-03-31' }],
      })
      expect(layout.start <= '2026-01-01' && layout.end >= '2027-03-31').toBe(
        true,
      )
    }
  })
  it('only stretches the fit scale when the viewport changes', () => {
    for (const zoom of ['day', 'week', 'month'] as const) {
      expect(
        timelineScale({ ...shortProject, zoom, availableWidth: 400 })
          .pixelsPerDay,
      ).toBe(
        timelineScale({ ...shortProject, zoom, availableWidth: 800 })
          .pixelsPerDay,
      )
    }
    const narrow = timelineScale({
      ...shortProject,
      zoom: 'fit',
      availableWidth: 400,
    })
    const wide = timelineScale({
      ...shortProject,
      zoom: 'fit',
      availableWidth: 800,
    })
    expect(wide.pixelsPerDay).toBe(narrow.pixelsPerDay * 2)
  })
  it('covers partial fit buckets without gaps or overlaps across a year boundary', () => {
    for (const unit of ['day', 'week', 'month'] as const) {
      const ticks = timelineTicks('2026-12-20', 35, unit)
      expect(ticks[0].offset).toBe(0)
      expect(ticks.reduce((sum, tick) => sum + tick.days, 0)).toBe(35)
      expect(ticks.at(-1)!.offset + ticks.at(-1)!.days).toBe(35)
      ticks
        .slice(1)
        .forEach((tick, i) =>
          expect(tick.offset).toBe(ticks[i].offset + ticks[i].days),
        )
    }
  })
  it('uses a finite range for unplanned activities and aligns explicit month anchors', () => {
    const layout = timelineScale({
      ...shortProject,
      tasks: [],
      activityDate: null,
      zoom: 'fit',
    })
    expect(Number.isFinite(layout.pixelsPerDay)).toBe(true)
    expect(layout.days).toBe(7)
    expect(
      timelineStart({ ...shortProject, zoom: 'month', anchor: '2027-01-27' }),
    ).toBe('2027-01-01')
  })
})
