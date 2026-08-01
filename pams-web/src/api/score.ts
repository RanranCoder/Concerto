import { get, post, put, del } from './http'
import type { ScoreRecordVO, ScoreRuleVO } from './activity'

export interface ScoreRuleSave {
  activityId: number
  dimensionName: string
  fullMarks: number
  sortOrder?: number | null
}

/** 评分记录：dimensionScores 为 JSON 字符串 {dimensionId: score}，total 由后端求和写入 */
export interface ScoreRecordSave {
  activityId: number
  teamName: string
  groupName?: string | null
  dimensionScores?: string | null
  rankNo?: number | null
  remark?: string | null
}

export const getScores = (activityId: number) =>
  get<{ rules: ScoreRuleVO[]; records: ScoreRecordVO[] }>('/scores', { activityId })

export const createScoreRule = (data: ScoreRuleSave) => post<number>('/scores/rules', data)
export const updateScoreRule = (id: number, data: ScoreRuleSave) => put<void>(`/scores/rules/${id}`, data)
export const deleteScoreRule = (id: number) => del<void>(`/scores/rules/${id}`)

export const createScoreRecord = (data: ScoreRecordSave) => post<number>('/scores/records', data)
export const updateScoreRecord = (id: number, data: ScoreRecordSave) => put<void>(`/scores/records/${id}`, data)
export const deleteScoreRecord = (id: number) => del<void>(`/scores/records/${id}`)
