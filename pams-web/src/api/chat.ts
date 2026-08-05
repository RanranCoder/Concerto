import { get, post, put, del } from './http'

// ==================== F06 群聊管理 ====================

export interface GroupChatCategoryVO {
  id: number
  name: string
  sortOrder: number
  createdAt: string
}

export interface GroupChatVO {
  id: number
  name: string
  categoryId: number | null
  categoryName: string | null
  activityId: number | null
  activityName: string | null
  ownerId: number | null
  ownerName: string | null
  qrCodeUrl: string
  remark: string
  status: 'ACTIVE' | 'DISSOLVED' | 'ARCHIVED'
  departments: string[]
  createdAt: string
  updatedAt: string
}

export interface GroupChatSave {
  name: string
  categoryId?: number | null
  activityId?: number | null
  departments?: string[]
  ownerId?: number | null
  qrCodeUrl?: string
  remark?: string
  status?: 'ACTIVE' | 'DISSOLVED' | 'ARCHIVED'
}

// 分类
export const listChatCategories = () => get<GroupChatCategoryVO[]>('/chat/categories')
export const createChatCategory = (name: string) => post<number>('/chat/categories', { name })
export const renameChatCategory = (id: number, name: string) => put<void>(`/chat/categories/${id}`, { name })
export const deleteChatCategory = (id: number) => del<void>(`/chat/categories/${id}`)
export const sortChatCategories = (ids: number[]) => put<void>('/chat/categories/sort', { ids })

// 群聊
export const listGroupChats = (params?: { keyword?: string; categoryId?: number; status?: string; department?: string }) =>
  get<GroupChatVO[]>('/chat', params)
export const getGroupChat = (id: number) => get<GroupChatVO>(`/chat/${id}`)
export const createGroupChat = (data: GroupChatSave) => post<number>('/chat', data)
export const updateGroupChat = (id: number, data: GroupChatSave) => put<void>(`/chat/${id}`, data)
export const archiveGroupChat = (id: number) => post<void>(`/chat/${id}/archive`)
export const deleteGroupChat = (id: number) => del<void>(`/chat/${id}`)
