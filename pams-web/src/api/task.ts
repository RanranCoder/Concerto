import { get, post, put, del } from './http'
import type { GanttTask } from '@/components/gantt/gantt.utils'

export interface TaskSave {
  activityId: number
  name: string
  deptId?: number | null
  assignee?: string | null
  startDate?: string | null
  endDate?: string | null
  dependsOn?: number | null
  isMilestone?: number
  progress?: number
  status?: string
  priority?: number
  description?: string | null
}

/** 后端 Task 实体：返回 deptId（不含 deptName），前端用部门列表映射 deptName 供甘特图着色 */
export interface TaskVO {
  id: number
  activityId: number
  name: string
  deptId: number | null
  assignee: string | null
  startDate: string | null
  endDate: string | null
  dependsOn: number | null
  isMilestone: number | null
  progress: number | null
  status: string
  priority: number | null
  description: string | null
  createdAt: string
  updatedAt: string
}

/** 后端 Task → 甘特图组件需要的形状（startDate/endDate 必填，deptName 由部门映射补全） */
export function toGanttTask(t: TaskVO, deptName?: string): GanttTask {
  return {
    id: t.id,
    name: t.name,
    startDate: t.startDate ?? '',
    endDate: t.endDate ?? '',
    dependsOn: t.dependsOn,
    deptName,
    isMilestone: (t.isMilestone ?? 0) === 1,
    progress: t.progress ?? 0,
    assignee: t.assignee ?? undefined,
  }
}

export const listTasks = (activityId: number) => get<TaskVO[]>('/tasks', { activityId })
export const createTask = (data: TaskSave) => post<TaskVO>('/tasks', data)
export const updateTask = (id: number, data: TaskSave) => put<void>(`/tasks/${id}`, data)
export const deleteTask = (id: number) => del<void>(`/tasks/${id}`)
export const updateTaskProgress = (id: number, progress: number) =>
  put<void>(`/tasks/${id}/progress`, { progress })
