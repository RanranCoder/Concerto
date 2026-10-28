import { get, post, put, del } from './http'
import type { GanttTask } from '@/components/gantt/gantt.utils'

export interface TaskSave {
  activityId: number
  name: string
  deptId?: number | null
  assignee?: string | null
  assigneeUserId?: number | null
  startDate?: string | null
  endDate?: string | null
  dependsOn?: number | null
  isMilestone?: number
  progress?: number
  status?: string
  priority?: number
  description?: string | null
  expectedRevision?: number
}

/** 后端 Task 实体：返回 deptId（不含 deptName），前端用部门列表映射 deptName 供甘特图着色 */
export interface TaskVO {
  id: number
  revision: number
  activityId: number
  name: string
  deptId: number | null
  assignee: string | null
  assigneeUserId?: number | null
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
    deptId: t.deptId,
    isMilestone: (t.isMilestone ?? 0) === 1,
    progress: t.progress ?? 0,
    assignee: t.assignee ?? undefined,
  }
}

export const listTasks = (activityId: number) => get<TaskVO[]>('/tasks', { activityId })
export const createTask = (data: TaskSave) => post<TaskVO>('/tasks', data)
export const updateTask = (id: number, data: TaskSave) => put<void>(`/tasks/${id}`, data)
export interface TaskPlanRequest {
  id?: number
  task: TaskSave
  previewToken?: string
  cascade?: boolean
}
export interface TaskPlanPreview {
  candidate: TaskVO
  impacts: Array<{ before: TaskVO; after: TaskVO }>
  token: string
}
export interface TaskPlanUndo {
  activityId: number
  entries: Array<{ id: number; expectedRevision: number; before: TaskSave | null }>
}
export interface TaskPlanResult { tasks: TaskVO[]; undo: TaskPlanUndo }
export const previewTaskPlan = (request: TaskPlanRequest) => post<TaskPlanPreview>('/tasks/plan/preview', request)
export const saveTaskPlan = (request: TaskPlanRequest) => post<TaskPlanResult>('/tasks/plan', request)
export const undoTaskPlan = (request: TaskPlanUndo) => post<TaskPlanResult>('/tasks/plan/undo', request)
export const deleteTask = (id: number, expectedRevision?: number) =>
  del<TaskPlanResult>(`/tasks/${id}`, { params: { expectedRevision } })
export const updateTaskProgress = (id: number, progress: number, expectedRevision?: number) =>
  put<TaskVO>(`/tasks/${id}/progress`, { progress, expectedRevision })

export function taskSave(task: TaskVO): TaskSave {
  return {
    activityId: task.activityId, name: task.name, deptId: task.deptId,
    assignee: task.assignee, assigneeUserId: task.assigneeUserId, startDate: task.startDate, endDate: task.endDate,
    dependsOn: task.dependsOn, isMilestone: task.isMilestone ?? 0,
    progress: task.progress ?? 0, priority: task.priority ?? 0,
    description: task.description, expectedRevision: task.revision,
  }
}
