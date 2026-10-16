package com.pams.module.knowledge.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.module.knowledge.dto.KbDocumentRequest;
import com.pams.module.knowledge.entity.KbChunk;
import com.pams.module.knowledge.entity.KbDocument;
import com.pams.module.knowledge.entity.KbRetrievalLog;
import com.pams.module.knowledge.entity.KbSnapshot;
import com.pams.module.knowledge.repository.KbChunkRepository;
import com.pams.module.knowledge.repository.KbDocumentRepository;
import com.pams.module.knowledge.repository.KbRetrievalLogRepository;
import com.pams.module.knowledge.repository.KbSnapshotRepository;
import com.pams.module.knowledge.retriever.KnowledgeSearchMode;
import com.pams.module.knowledge.snapshot.SnapshotGenerator;
import com.pams.module.knowledge.snapshot.SnapshotSpec;
import com.pams.module.knowledge.snapshot.SnapshotSpecRegistry;
import com.pams.module.knowledge.source.KnowledgeSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识库的读取与运维服务（知识库页面的后端）。
 *
 * <p><b>这个服务存在的理由比"给页面提供数据"更实在</b>：知识库把散落在各业务表的正文
 * 做成了可检索副本，就必须有能力回答两件事 ——
 * ①"现在库里有什么"（内容台账）②"谁看到过什么、为什么没答"（检索记录）。
 * 没有它们，出了越权或答错都无从追溯，也没法判断"该补语料还是该修索引"。
 */
@Slf4j
@Service
public class KnowledgeAdminService {
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private PermissionResolver accessResolver;
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private org.springframework.jdbc.core.JdbcTemplate accessJdbc;

    /** Enumerate candidate metadata, then resolve source access before pagination/counting. */
    private java.util.Set<Long> visibleIds(String table,String sourceType) {
        if (accessJdbc == null || accessResolver == null) return java.util.Set.of();
        var ctx = com.pams.module.agent.core.ToolContext.current(null,null);
        if (ctx.getUserId()==null) return java.util.Set.of();
        Long org=com.pams.common.OrgContext.get();
        if (org==null) throw new BizException(403,"知识库访问缺少组织上下文");
        String sql = switch(table) {
            case "kb_chunk" -> "SELECT id,source_type,source_id FROM kb_chunk WHERE org_id=? AND deleted=0";
            case "kb_document" -> "SELECT id,created_by FROM kb_document WHERE org_id=? AND deleted=0";
            case "kb_snapshot" -> "SELECT id FROM kb_snapshot WHERE org_id=? AND deleted=0";
            default -> throw new IllegalArgumentException("Unsupported knowledge table");
        };
        var allowed=new java.util.HashSet<Long>();
        var cache=new java.util.HashMap<String,Boolean>();
        for(var row:accessJdbc.queryForList(sql,org)) {
            long id=((Number)row.get("id")).longValue();
            String type=sourceType==null?row.get("source_type").toString():sourceType;
            long source=sourceType==null?((Number)row.get("source_id")).longValue():id;
            boolean accessible=cache.computeIfAbsent(type+":"+source,key -> accessResolver.canAccess(type,source,ctx));
            if(table.equals("kb_document") && ctx.hasPermission("kb:manage") && java.util.Objects.equals(row.get("created_by"),ctx.getUserId())) accessible=true;
            if(accessible) allowed.add(id);
        }
        return allowed;
    }

    private List<Map<String,Object>> visibleMetadata(String table,String columns,java.util.Set<Long> ids) {
        if (ids.isEmpty() || accessJdbc == null) return List.of();
        if (!java.util.Set.of("kb_chunk","kb_snapshot").contains(table)) throw new IllegalArgumentException();
        return accessJdbc.queryForList("SELECT "+columns+" FROM "+table+" WHERE org_id=? AND deleted=0",com.pams.common.OrgContext.get()).stream()
            .filter(row -> ids.contains(((Number)row.get("id")).longValue())).toList();
    }

    /** 引用片段在台账/记录里展示的截断长度 */
    private static final int SNIPPET_CHARS = 200;

    private final KbDocumentRepository docRepository;
    private final KbChunkRepository chunkRepository;
    private final KbRetrievalLogRepository logRepository;
    private final KbSnapshotRepository snapshotRepository;
    private final KnowledgeIndexer indexer;
    private final KnowledgeSearchMode searchMode;
    private final SnapshotGenerator snapshotGenerator;
    private final SnapshotSpecRegistry specRegistry;

    public KnowledgeAdminService(KbDocumentRepository docRepository,
                                KbChunkRepository chunkRepository,
                                KbRetrievalLogRepository logRepository,
                                KbSnapshotRepository snapshotRepository,
                                KnowledgeIndexer indexer,
                                KnowledgeSearchMode searchMode,
                                SnapshotGenerator snapshotGenerator,
                                SnapshotSpecRegistry specRegistry) {
        this.docRepository = docRepository;
        this.chunkRepository = chunkRepository;
        this.logRepository = logRepository;
        this.snapshotRepository = snapshotRepository;
        this.indexer = indexer;
        this.searchMode = searchMode;
        this.snapshotGenerator = snapshotGenerator;
        this.specRegistry = specRegistry;
    }

    /* ==================================================================== 源清单与统计 */

    /**
     * 源清单：每个知识源收录了什么、什么分级、权重多少、当前覆盖多少条。
     *
     * <p>这个接口是**评审入口**：想确认"系统自动把哪些数据放进了知识库"，
     * 看这一份就够了 —— 每个源的两句话说明（收了什么、为什么这个分级）
     * 就是当初做决策时的理由，写在这里才不会被忘掉。
     */
    public List<Map<String, Object>> sources() {
        var readable = visibleMetadata("kb_chunk", "id,source_type,source_id,indexed_at", visibleIds("kb_chunk", null));
        List<Map<String, Object>> out = new ArrayList<>();
        for (KnowledgeSource s : indexer.sources()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("sourceType", s.sourceType());
            m.put("label", s.label());
            m.put("description", s.description());
            m.put("tier", s.tier().name());
            m.put("weight", s.weight());
            m.put("visibility", s.visibility());
            m.put("requiredPermission", s.requiredPermission());
            m.put("sensitivity", s.sensitivity());
            m.put("documentCount", readable.stream().filter(r -> s.sourceType().equals(r.get("source_type"))).map(r -> r.get("source_id")).distinct().count());
            m.put("chunkCount", readable.stream().filter(r -> s.sourceType().equals(r.get("source_type"))).count());
            out.add(m);
        }
        return out;
    }

    /** 覆盖统计 + 检索健康度 */
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("retriever", searchMode.mode());
        out.put("fulltextAvailable", searchMode.fulltextAvailable());
        out.put("searchModeNote", searchMode.note());
        var docs = visibleIds("kb_document", "manual");
        var chunks = visibleMetadata("kb_chunk", "id,source_type,source_id,indexed_at", visibleIds("kb_chunk", null));
        var snapshots = visibleMetadata("kb_snapshot", "id,spec_code,as_of", visibleIds("kb_snapshot", "snapshot"));
        out.put("documentCount", docs.isEmpty() ? 0 : docRepository.count((root,q,cb) -> cb.and(root.get("id").in(docs), cb.equal(root.get("status"), KbDocument.STATUS_PUBLISHED))));
        out.put("chunkCount", chunks.size());

        // 事实快照（D12 的 B 形态）：单独报出来，因为它是"数据类问题能不能答"的开关
        out.put("snapshotCount", snapshots.size());
        out.put("snapshotSpecCount", snapshots.stream().map(r -> r.get("spec_code")).distinct().count());
        out.put("snapshotLatestAsOf", snapshots.stream().map(r -> (java.sql.Timestamp)r.get("as_of")).filter(java.util.Objects::nonNull).max(java.util.Comparator.naturalOrder()).map(java.sql.Timestamp::toLocalDateTime).orElse(null));

        // 台账/记录的"知识库更新于…"用这个值 —— 取最近一次成功索引时间，
        // 而不是"任务运行时间"，否则任务失败时会显示假的新鲜度（方案 R19）
        LocalDateTime last = chunks.stream().map(r -> (java.sql.Timestamp)r.get("indexed_at")).filter(java.util.Objects::nonNull).max(java.util.Comparator.naturalOrder()).map(java.sql.Timestamp::toLocalDateTime).orElse(null);
        out.put("lastIndexedAt", last);
        out.put("staleMinutes", last == null ? null
                : java.time.Duration.between(last, LocalDateTime.now()).toMinutes());

        // 检索健康度：拒答率突然升高 = 索引坏了（方案 §11 的强信号）
        Long requester = com.pams.module.agent.core.ToolContext.current(null,null).getUserId();
        Specification<KbRetrievalLog> own = (root,q,cb) -> requester == null ? cb.disjunction() : cb.equal(root.get("userId"),requester);
        long total = logRepository.count(own);
        long noHit = logRepository.count(own.and((root,q,cb) -> cb.equal(root.get("skipReason"),KbRetrievalLog.SKIP_NO_HIT)));
        long noCitation = logRepository.count(own.and((root,q,cb) -> cb.equal(root.get("skipReason"),KbRetrievalLog.SKIP_NO_CITATION)));
        long filtered = logRepository.count(own.and((root,q,cb) -> cb.greaterThan(root.get("filteredCount"),0)));
        out.put("retrievalTotal", total);
        out.put("retrievalNoHit", noHit);
        out.put("retrievalNoCitation", noCitation);
        // filtered > 0 是"索引期可见性字段偏松"的体检信号
        out.put("retrievalFiltered", filtered);
        return out;
    }

    /* ==================================================================== 事实快照 */

    /**
     * 快照清单（知识库页面「内容台账」里的一个来源，也可单独看）。
     *
     * <p>返回 {@code content} 全文 —— 快照本身就是一段短叙述，
     * 而且它存在的意义就是让人核对"这个数字对不对"，藏起来反而没用。
     */
    public PageResult<Map<String, Object>> pageSnapshots(String specCode, int page, int size) {
        var allowed=visibleIds("kb_snapshot","snapshot");
        PageRequest pr = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 200),
                Sort.by(Sort.Direction.DESC, "asOf"));
        Page<KbSnapshot> p = snapshotRepository.findAll((root,query,cb) -> cb.and(
                allowed.isEmpty()?cb.disjunction():root.get("id").in(allowed),
                specCode==null||specCode.isBlank()?cb.conjunction():cb.equal(root.get("specCode"),specCode.trim())),pr);

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(this::snapshotVo).toList());
        r.setTotal(p.getTotalElements());
        r.setCurrent(page);
        r.setSize(size);
        return r;
    }

    private Map<String, Object> snapshotVo(KbSnapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", s.getId());
        m.put("specCode", s.getSpecCode());
        m.put("title", s.getTitle());
        m.put("content", s.getContent());
        m.put("asOf", s.getAsOf());
        m.put("scopeType", s.getScopeType());
        m.put("scopeId", s.getScopeId());
        m.put("visibility", s.getVisibility());
        m.put("requiredPermission", s.getRequiredPermission());
        m.put("generatedAt", s.getGeneratedAt());
        SnapshotSpec spec = specRegistry.byCode(s.getSpecCode());
        // 把 spec 的说明一并带出来：快照列表本身就是"系统自动收录了什么"的说明页
        m.put("description", spec == null ? null : spec.description());
        m.put("refreshHint", spec == null ? null : spec.refreshHint());
        return m;
    }

    /** 手工触发生成（kb:manage）。生成完顺带跑一次保留策略清理 */
    public Map<String, Object> refreshSnapshots() {
        Map<String, Object> report = snapshotGenerator.generateAll();
        int pruned = snapshotGenerator.pruneOldSnapshots();
        report.put("pruned", pruned);
        return report;
    }

    /** 快照模板清单（评审入口：系统会自动收录哪些聚合数据、给谁看） */
    public List<Map<String, Object>> snapshotSpecs() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (SnapshotSpec s : specRegistry.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("code", s.code());
            m.put("description", s.description());
            m.put("titleTemplate", s.titleTemplate());
            m.put("scopeType", s.scopeType());
            m.put("visibility", s.visibility());
            m.put("visibilityLabel", s.visibilityLabel());
            m.put("requiredPermission", s.requiredPermission());
            m.put("link", s.link());
            m.put("refreshHint", s.refreshHint());
            m.put("scopeCount", visibleMetadata("kb_snapshot", "id,spec_code", visibleIds("kb_snapshot", "snapshot")).stream().filter(row -> s.code().equals(row.get("spec_code"))).count());
            out.add(m);
        }
        return out;
    }

    /* ==================================================================== 内容台账 */

    /**
     * 分块台账（知识库页面第一个 Tab）。
     *
     * <p>直接以**分块**为单位而不是文档：这就是"Agent 已经检索到的所有数据"的字面形态 ——
     * 检索命中的单元是分块，用户想核对的也是分块。
     */
    public PageResult<Map<String, Object>> pageChunks(String sourceType, String keyword,
                                                     int page, int size) {
        var allowed=visibleIds("kb_chunk",null);
        Specification<KbChunk> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            ps.add(allowed.isEmpty()?cb.disjunction():root.get("id").in(allowed));
            if (sourceType != null && !sourceType.isBlank()) {
                ps.add(cb.equal(root.get("sourceType"), sourceType));
            }
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                ps.add(cb.or(cb.like(root.get("title"), like),
                        cb.like(root.get("content"), like),
                        cb.like(root.get("path"), like)));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<KbChunk> p = chunkRepository.findAll(spec,
                PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 200),
                        Sort.by(Sort.Direction.DESC, "sourceType").and(Sort.by("sourceId", "seq"))));

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(this::chunkVo).toList());
        r.setTotal(p.getTotalElements());
        r.setCurrent(page);
        r.setSize(size);
        return r;
    }

    private Map<String, Object> chunkVo(KbChunk c) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("sourceType", c.getSourceType());
        m.put("sourceId", c.getSourceId());
        m.put("sourceKey", c.getSourceKey());
        m.put("seq", c.getSeq());
        m.put("title", c.getTitle());
        m.put("path", c.getPath());
        m.put("tier", c.getTier());
        m.put("sensitivity", c.getSensitivity());
        m.put("asOf", c.getAsOf());
        m.put("indexedAt", c.getIndexedAt());
        m.put("tokenLen", c.getTokenLen());
        m.put("snippet", snippet(c.getContent()));
        return m;
    }

    private String snippet(String content) {
        if (content == null) return "";
        String flat = content.replace('\n', ' ').strip();
        return flat.length() <= SNIPPET_CHARS ? flat : flat.substring(0, SNIPPET_CHARS) + "…";
    }

    /** 某个源记录的完整分块（点开台账行时用；这里才给完整正文） */
    public List<Map<String, Object>> chunksOf(String sourceType, Long sourceId) {
        if (accessResolver == null || !accessResolver.canAccess(sourceType,sourceId,com.pams.module.agent.core.ToolContext.current(null,null))) throw new BizException(404,"知识源不存在或不可见");
        List<Map<String, Object>> out = new ArrayList<>();
        for (KbChunk c : chunkRepository.findBySourceTypeAndSourceIdOrderBySeqAsc(sourceType, sourceId)) {
            Map<String, Object> m = chunkVo(c);
            m.put("content", c.getContent());   // 完整正文，覆盖 snippet
            out.add(m);
        }
        return out;
    }

    /* ==================================================================== 人工文档 CRUD */

    public PageResult<Map<String, Object>> pageDocuments(String status, String keyword,
                                                        int page, int size) {
        var allowed=visibleIds("kb_document","manual");
        Specification<KbDocument> spec = (root, query, cb) -> {
            List<jakarta.persistence.criteria.Predicate> ps = new ArrayList<>();
            ps.add(allowed.isEmpty()?cb.disjunction():root.get("id").in(allowed));
            if (status != null && !status.isBlank()) ps.add(cb.equal(root.get("status"), status));
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                ps.add(cb.or(cb.like(root.get("title"), like), cb.like(root.get("category"), like)));
            }
            return cb.and(ps.toArray(new jakarta.persistence.criteria.Predicate[0]));
        };

        Page<KbDocument> p = docRepository.findAll(spec,
                PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 100),
                        Sort.by(Sort.Direction.DESC, "id")));

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(this::docVo).toList());
        r.setTotal(p.getTotalElements());
        r.setCurrent(page);
        r.setSize(size);
        return r;
    }

    private Map<String, Object> docVo(KbDocument d) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", d.getId());
        m.put("title", d.getTitle());
        m.put("docType", d.getDocType());
        m.put("category", d.getCategory());
        m.put("summary", d.getSummary());
        m.put("version", d.getVersion());
        m.put("effectiveFrom", d.getEffectiveFrom());
        m.put("effectiveTo", d.getEffectiveTo());
        m.put("visibility", d.getVisibility());
        m.put("requiredPermission", d.getRequiredPermission());
        m.put("status", d.getStatus());
        m.put("updatedAt", d.getUpdatedAt());
        m.put("chunkCount", chunkRepository
                .findBySourceTypeAndSourceIdOrderBySeqAsc("manual", d.getId()).size());
        return m;
    }

    public Map<String, Object> document(Long id) {
        if (!visibleIds("kb_document","manual").contains(id)) throw new BizException(404,"文档不存在或不可见");
        KbDocument d = docRepository.findById(id)
                .orElseThrow(() -> new BizException(4404, "文档不存在"));
        Map<String, Object> m = docVo(d);
        m.put("body", d.getBody());     // 详情才给正文
        return m;
    }

    /**
     * 录入文档（写库后立即增量索引）。
     *
     * <p>为什么同步索引而不是等定时任务：用户刚录完制度就去问"这条规定怎么写的"，
     * 如果查不到，第一反应是"这个功能没用" —— 而这恰好是知识库最需要建立信任的时刻。
     * 单篇文档的分块只有几十条，同步索引的延迟可以忽略。
     */
    @Transactional
    public Long createDocument(KbDocumentRequest req, Long userId) {
        KbDocument d = new KbDocument();
        apply(d, req);
        d.setDeleted(0);
        d.setCreatedBy(userId);
        d.setCreatedAt(LocalDateTime.now());
        d.setUpdatedAt(LocalDateTime.now());
        KbDocument saved = docRepository.save(d);

        indexer.reindexSource(ManualDocSourceType(), saved.getId());
        return saved.getId();
    }

    @Transactional
    public void updateDocument(Long id, KbDocumentRequest req) {
        if (!visibleIds("kb_document", "manual").contains(id)) throw new BizException(404,"文档不存在或不可见");
        KbDocument d = docRepository.findById(id)
                .orElseThrow(() -> new BizException(4404, "文档不存在"));
        apply(d, req);
        d.setUpdatedAt(LocalDateTime.now());
        docRepository.save(d);

        // 内容变了必须重建索引，否则答案依据的还是旧正文（方案 R6）
        indexer.reindexSource(ManualDocSourceType(), id);
    }

    @Transactional
    public void deleteDocument(Long id) {
        if (!visibleIds("kb_document", "manual").contains(id)) throw new BizException(404,"文档不存在或不可见");
        KbDocument d = docRepository.findById(id)
                .orElseThrow(() -> new BizException(4404, "文档不存在"));
        d.setDeleted(1);
        d.setUpdatedAt(LocalDateTime.now());
        docRepository.save(d);

        // 源删了必须清分块，否则知识库会答出已不存在的内容（方案 §2.3 删除语义）
        indexer.reindexSource(ManualDocSourceType(), id);
    }

    private String ManualDocSourceType() { return "manual"; }

    private void apply(KbDocument d, KbDocumentRequest req) {
        d.setTitle(req.title() == null ? null : req.title().trim());
        d.setDocType(blankTo(req.docType(), KbDocument.TYPE_REGULATION));
        d.setCategory(req.category());
        d.setBody(req.body());
        d.setSummary(req.summary());
        d.setVersion(req.version());
        d.setEffectiveFrom(req.effectiveFrom());
        d.setEffectiveTo(req.effectiveTo());
        d.setVisibility(blankTo(req.visibility(), "ALL"));
        d.setRequiredPermission(req.requiredPermission());
        d.setStatus(blankTo(req.status(), KbDocument.STATUS_PUBLISHED));
    }

    private String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /* ==================================================================== 重建与检索记录 */

    /** 手动全量重建 */
    @Transactional
    public Map<String, Object> reindexAll() {
        Map<String, Integer> perSource = indexer.reindexAll();
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("perSource", perSource);
        out.put("totalChunks", chunkRepository.count());
        out.put("lastIndexedAt", chunkRepository.lastIndexedAt());
        return out;
    }

    /**
     * 检索记录（知识库页面第二个 Tab）。
     *
     * <p>{@code skipReason} 可筛 —— 用它看"最近为什么老是答不上"：
     * 全 NO_HIT 是语料不足，伴随 filtered &gt; 0 是权限粗筛偏松，
     * 大量 NO_CITATION 则是模型没标编号（prompt 或映射逻辑问题）。
     */
    public PageResult<Map<String, Object>> pageRetrievals(String skipReason, int page, int size) {
        PageRequest pr = PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 100));
        Long requester = com.pams.module.agent.core.ToolContext.current(null,null).getUserId();
        Page<KbRetrievalLog> p = logRepository.findAll((root,query,cb) -> cb.and(
                requester==null?cb.disjunction():cb.equal(root.get("userId"),requester),
                skipReason==null||skipReason.isBlank()?cb.conjunction():cb.equal(root.get("skipReason"),skipReason)),
                PageRequest.of(pr.getPageNumber(),pr.getPageSize(),Sort.by(Sort.Direction.DESC,"id")));

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(this::logVo).toList());
        r.setTotal(p.getTotalElements());
        r.setCurrent(page);
        r.setSize(size);
        return r;
    }

    private Map<String, Object> logVo(KbRetrievalLog l) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", l.getId());
        m.put("userId", l.getUserId());
        m.put("conversationId", l.getConversationId());
        m.put("queryText", l.getQueryText());
        m.put("mode", l.getMode());
        m.put("hitCount", l.getHitCount());
        m.put("allowedCount", l.getAllowedCount());
        m.put("filteredCount", l.getFilteredCount());
        m.put("topScore", l.getTopScore());
        m.put("scanMs", l.getScanMs());
        m.put("totalMs", l.getTotalMs());
        m.put("skipReason", l.getSkipReason());
        m.put("provider", l.getProvider());
        m.put("degraded", l.getDegraded() != null && l.getDegraded() == 1);
        m.put("hitsJson", visibleHits(l.getHitsJson()));
        m.put("createdAt", l.getCreatedAt());
        return m;
    }
    private String visibleHits(String raw) {
        if (raw == null || accessResolver == null) return "[]";
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            var entries = mapper.readTree(raw);
            var output = mapper.createArrayNode();
            var ctx = com.pams.module.agent.core.ToolContext.current(null,null);
            for (var item : entries) if (item.hasNonNull("sourceType") && item.hasNonNull("sourceId") && accessResolver.canAccess(item.path("sourceType").asText(), item.path("sourceId").asLong(), ctx)) output.add(item);
            return output.toString();
        } catch (Exception e) { return "[]"; }
    }

}
