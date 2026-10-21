import { get, post, put, del } from './http'

/* ============================================================================
 * 知识库 API（灵思「对话」档位的运维与展示面）
 * 设计文档：docs/PAMS-灵思知识库问答方案-v1.md（D12 全域知识源）
 * ========================================================================== */

/** 知识源分级：T1 全文级（参与 BM25）/ T2 字段级（只做标题匹配） */
export type KnowledgeTier = 'T1' | 'T2'

/**
 * 源清单项。
 *
 * `description` 不是装饰：它是当初决定"这个源收不收、算不算正文语料"的理由，
 * 写在这里才不会被忘掉 —— 这个接口本身就是**评审入口**。
 */
export interface KnowledgeSourceVO {
  sourceType: string
  label: string
  description: string
  tier: KnowledgeTier
  /** 源权重（权威度）；未声明权重的源不会参与打分 */
  weight: number
  visibility: string
  requiredPermission: string | null
  sensitivity: string
  /** 该源在索引里的"文档"数（去重后的 source_id 数） */
  documentCount: number
  chunkCount: number
}

export interface KnowledgeStatsVO {
  entryCount: number
  retrievalUnanswered: number
  /** 实际生效的检索实现：fulltext-ngram / like-fallback */
  retriever: string
  fulltextAvailable: boolean
  /** 降级原因说明 —— 排查时这句话比布尔值有用 */
  searchModeNote: string
  documentCount: number
  chunkCount: number
  /** 已生成的事实快照份数（D12 的 B 形态） */
  snapshotCount?: number
  /** 有几种快照模板 */
  snapshotSpecCount?: number
  /** 所有快照里最新的数据截止时间 */
  snapshotLatestAsOf?: string | null
  /** 最近一次**成功**索引时间（不是任务运行时间，避免假的新鲜度） */
  lastIndexedAt: string | null
  staleMinutes: number | null
  retrievalTotal: number
  retrievalNoHit: number
  retrievalNoCitation: number
  /** 粗筛放过但被权限复核拦下的次数；>0 说明索引期可见性字段偏松 */
  retrievalFiltered: number
}

/** 内容台账的一行 = 一个检索单元（分块） */
export interface KnowledgeChunkVO {
  id: number
  sourceType: string
  sourceId: number
  sourceKey: string
  seq: number
  title: string | null
  /** 标题路径，如「第三章 第二十六条」—— 引用的可核验性靠它 */
  path: string | null
  tier: KnowledgeTier
  sensitivity: string
  /** 数据截止时间；仅快照类源有值 */
  asOf: string | null
  indexedAt: string | null
  tokenLen: number | null
  snippet: string
  /** 仅 `chunksOf` 返回（台账行展开时才有完整正文） */
  content?: string
}

export interface KnowledgeEntryVO extends KnowledgeChunkVO {
  chunkCount: number
  sourceLabel: string
  visibility: string
  requiredPermission: string | null
  link: string | null
}

export const kbEntries = (params: {sourceType?: string; keyword?: string; tier?: KnowledgeTier; sort?: 'updated' | 'title'; page?: number; size?: number}) =>
  get<KbPage<KnowledgeEntryVO>>('/kb/entries', params)

export interface KbPage<T> {
  records: T[]
  total: number
  current: number
  size: number
}

/** 人工录入文档（ManualDocSource） */
export interface KbDocumentVO {
  id: number
  title: string
  docType: string
  category: string | null
  summary: string | null
  version: string | null
  effectiveFrom: string | null
  effectiveTo: string | null
  visibility: string
  requiredPermission: string | null
  status: string
  updatedAt: string | null
  chunkCount: number
  body?: string
}

export interface KbDocumentRequest {
  title: string
  docType?: string
  category?: string
  body: string
  summary?: string
  version?: string
  effectiveFrom?: string | null
  /** 废止日；留空 = 长期有效 */
  effectiveTo?: string | null
  visibility?: string
  requiredPermission?: string
  status?: string
}

/**
 * 检索记录项。
 *
 * 三个计数的关系是理解这条记录的关键：
 * `hitCount`（粗筛命中）→ `filteredCount`（权限复核拦下）→ `allowedCount`（真正进 prompt）
 * 当 `filteredCount > 0` 时，说明索引期写入的可见性字段比实际权限宽 ——
 * 这是两段式权限设计的自体检指标。
 */
export interface KbRetrievalLogVO {
  id: number
  userId: number
  conversationId: number | null
  queryText: string
  mode: string
  hitCount: number
  allowedCount: number
  filteredCount: number
  topScore: number | null
  scanMs: number | null
  totalMs: number | null
  /** NO_HIT / BELOW_THRESHOLD / NO_CITATION；null = 正常作答 */
  skipReason: string | null
  provider: string | null
  degraded: boolean
  hitsJson: string | null
  createdAt: string
}

/* ------------------------------------------------------------------ 源与统计 */

export const kbSources = () => get<KnowledgeSourceVO[]>('/kb/sources')

export const kbStats = () => get<KnowledgeStatsVO>('/kb/stats')

/* ------------------------------------------------------------------ 内容台账 */

export const kbChunks = (params: { sourceType?: string; keyword?: string; page?: number; size?: number } = {}) =>
  get<KbPage<KnowledgeChunkVO>>('/kb/chunks', {
    sourceType: params.sourceType,
    keyword: params.keyword,
    page: params.page ?? 1,
    size: params.size ?? 20,
  })

/** 某个源记录的完整分块（台账行展开时用） */
export const kbChunksOf = (sourceType: string, sourceId: number) =>
  get<KnowledgeChunkVO[]>(`/kb/chunks/${encodeURIComponent(sourceType)}/${sourceId}`)

/* ------------------------------------------------------------------ 人工文档 */

export const kbDocuments = (params: { status?: string; keyword?: string; page?: number; size?: number } = {}) =>
  get<KbPage<KbDocumentVO>>('/kb/documents', {
    status: params.status,
    keyword: params.keyword,
    page: params.page ?? 1,
    size: params.size ?? 20,
  })

export const kbDocument = (id: number) => get<KbDocumentVO>(`/kb/documents/${id}`)

export const kbCreateDocument = (body: KbDocumentRequest) =>
  post<number>('/kb/documents', body)

export const kbUpdateDocument = (id: number, body: KbDocumentRequest) =>
  put<void>(`/kb/documents/${id}`, body)

export const kbDeleteDocument = (id: number) => del<void>(`/kb/documents/${id}`)

/* ------------------------------------------------------------------ 运维与检索记录 */

/** 手动全量重建索引（每日兜底任务走同一逻辑） */
export const kbReindex = () =>
  post<{ perSource: Record<string, number>; totalChunks: number; lastIndexedAt: string | null }>(
    '/kb/reindex',
  )

export const kbRetrievals = (params: { skipReason?: string; keyword?: string; page?: number; size?: number } = {}) =>
  get<KbPage<KbRetrievalLogVO>>('/kb/retrievals', {
    skipReason: params.skipReason,
    keyword: params.keyword,
    page: params.page ?? 1,
    size: params.size ?? 20,
  })

/* ------------------------------------------------------------------ 事实快照 */

/**
 * 快照模板（D12 的 B 形态）。
 *
 * **这个列表就是隐私与权限的评审入口**：想知道"灵思会自动把哪些业务数据写进知识库"，
 * 看这一份就够 —— 每个模板都写明收什么、给谁看、为什么。
 */
export interface KbSnapshotSpecVO {
  code: string
  description: string
  titleTemplate: string
  scopeType: string
  /** ALL / LEADER —— 必须与来源页面的守卫口径一致（没有默认值） */
  visibility: 'ALL' | 'LEADER'
  /** 人读的可见范围描述 */
  visibilityLabel: string
  requiredPermission: string | null
  /** 出处跳转；null = 没有可跳转页面 */
  link: string | null
  /** 刷新频率的人读描述（实际频率由后端定时任务决定） */
  refreshHint: string
  /** 作用域数量（如按届别生成则等于届别数） */
  scopeCount: number
}

/**
 * 一份已生成的事实快照。
 *
 * **`asOf` 是这张表的灵魂**：它声明"这是某时刻的数字"而不是"当前值"。
 * 前端必须展示它 —— 用户拿过期数字做决策比不答更糟（方案 R16）。
 *
 * `content` 是聚合叙述（"本届成员共 12 人…"），**结构上不含个人明细**：
 * 后端取值口只接受计数与组织名，且 kb3.mjs 有端到端断言兜底。
 */
export interface KbSnapshotVO {
  id: number
  specCode: string
  title: string
  content: string
  /** 数据截止时间（YYYY-MM-DDTHH:mm） */
  asOf: string
  scopeType: string
  scopeId: number
  visibility: string
  requiredPermission: string | null
  generatedAt: string | null
  /** 该模板的评审说明（后端随行返回，便于就地解释"这是怎么算出来的"） */
  description: string | null
  refreshHint: string | null
}

export interface KbSnapshotRefreshReport {
  totalWritten: number
  totalSkipped: number
  pruned: number
  lastAsOf: string | null
  /** 每个 spec 的生成/跳过数 */
  [specCode: string]: unknown
}

export const kbSnapshotSpecs = () => get<KbSnapshotSpecVO[]>('/kb/snapshot-specs')

export const kbSnapshots = (params: { specCode?: string; keyword?: string; page?: number; size?: number } = {}) =>
  get<KbPage<KbSnapshotVO>>('/kb/snapshots', {
    specCode: params.specCode,
    keyword: params.keyword,
    page: params.page ?? 1,
    size: params.size ?? 20,
  })

/** 手工重算全部快照（日常由定时任务按日生成；用于"刚录完人想立刻能问到"） */
export const kbRefreshSnapshots = () => post<KbSnapshotRefreshReport>('/kb/snapshots/refresh')
