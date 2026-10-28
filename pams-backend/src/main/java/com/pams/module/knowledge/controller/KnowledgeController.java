package com.pams.module.knowledge.controller;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.knowledge.dto.KbDocumentRequest;
import com.pams.module.knowledge.service.KnowledgeAdminService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 知识库接口（灵思「对话」档位的运维与展示面）。
 *
 * <h3>权限口径</h3>
 * <ul>
 *   <li>{@code kb:read} —— 查看内容台账与检索记录。**全部角色都有**（V27 种子），
 *       与 {@code /agent} 对所有登录用户开放一致：知识问答本身是人人都能用的能力，
 *       能看到哪些内容由各源的可见性决定，不由这个码决定。</li>
 *   <li>{@code kb:document:manage} —— 录入文档、重建索引。只给领导角色（V27 种子）。</li>
 * </ul>
 *
 * <h3>两个 Tab 对应两组接口</h3>
 * <pre>
 * 内容台账   GET /chunks            已索引进知识库的全部分块（这就是"Agent 能检索到的所有数据"）
 *            GET /chunks/{type}/{id} 某个源记录的完整分块（点开行看全文）
 *            GET /sources  /stats    源清单与覆盖统计
 * 检索记录   GET /retrievals        历次问答的检索流水（审计 + 运营）
 * </pre>
 */
@RestController
@RequestMapping("/api/kb")
public class KnowledgeController {

    private final KnowledgeAdminService service;

    public KnowledgeController(KnowledgeAdminService service) {
        this.service = service;
    }

    /* ==================================================================== 源与统计 */

    /** 源清单：每个源收了什么、什么分级、权重、当前覆盖 */
    @GetMapping("/sources")
    @PreAuthorize("hasAuthority('kb:read')")
    public Result<List<Map<String, Object>>> sources() {
        return Result.ok(service.sources());
    }

    /** 覆盖统计 + 检索健康度（含"知识库更新于…"，取最近一次**成功**索引时间） */
    @GetMapping("/stats")
    @PreAuthorize("hasAuthority('kb:read')")
    public Result<Map<String, Object>> stats() {
        return Result.ok(service.stats());
    }

    /** 检索模式健康检查：全文索引是否可用、是否已降级为 LIKE（方案 R14） */
    @GetMapping("/health")
    @PreAuthorize("isAuthenticated()")
    public Result<Map<String, Object>> health() {
        Map<String, Object> s = service.stats();
        return Result.ok(Map.of(
                "retriever", s.get("retriever"),
                "fulltextAvailable", s.get("fulltextAvailable"),
                "note", s.get("searchModeNote")));
    }

    /* ==================================================================== 内容台账 */

    @GetMapping("/entries")
    @PreAuthorize("hasAuthority('kb:read')")
    public Result<PageResult<Map<String, Object>>> entries(
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String tier,
            @RequestParam(defaultValue = "updated") String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(service.pageEntries(sourceType, keyword, tier, sort, page, size));
    }

    /** 分块台账（第一个 Tab）：全部分块，可按来源类型与关键词筛选 */
    @GetMapping("/chunks")
    @PreAuthorize("hasAuthority('kb:read')")
    public Result<PageResult<Map<String, Object>>> chunks(
            @RequestParam(required = false) String sourceType,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(service.pageChunks(sourceType, keyword, page, size));
    }

    /** 某个源记录的完整分块（台账行展开时用；此时才返回完整正文） */
    @GetMapping("/chunks/{sourceType}/{sourceId}")
    @PreAuthorize("hasAuthority('kb:read')")
    public Result<List<Map<String, Object>>> chunksOf(@PathVariable String sourceType,
                                                     @PathVariable Long sourceId) {
        return Result.ok(service.chunksOf(sourceType, sourceId));
    }

    /* ==================================================================== 人工文档 */

    @GetMapping("/documents")
    @PreAuthorize("hasAuthority('kb:read')")
    public Result<PageResult<Map<String, Object>>> documents(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(service.pageDocuments(status, keyword, page, size));
    }

    @GetMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('kb:read')")
    public Result<Map<String, Object>> document(@PathVariable Long id) {
        return Result.ok(service.document(id));
    }

    @PostMapping("/documents")
    @PreAuthorize("hasAuthority('kb:document:manage')")
    public Result<Long> createDocument(@Valid @RequestBody KbDocumentRequest req,
                                      @AuthenticationPrincipal LoginUser user) {
        return Result.ok(service.createDocument(req, user == null ? null : user.getId()));
    }

    @PutMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('kb:document:manage')")
    public Result<Void> updateDocument(@PathVariable Long id,
                                      @Valid @RequestBody KbDocumentRequest req) {
        service.updateDocument(id, req);
        return Result.ok();
    }

    @DeleteMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('kb:document:manage')")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        service.deleteDocument(id);
        return Result.ok();
    }

    /* ==================================================================== 重建与检索记录 */

    /** 手动全量重建索引（运维入口；每日兜底任务也走同一逻辑） */
    @PostMapping("/reindex")
    @PreAuthorize("hasAuthority('kb:index:manage')")
    public Result<Map<String, Object>> reindex() {
        return Result.ok(service.reindexAll());
    }

    /** 检索记录（第二个 Tab）：可读检索流水；按 skipReason 筛选看"为什么答不上" */
    @GetMapping("/retrievals")
    @PreAuthorize("hasAuthority('kb:read')")
    public Result<PageResult<Map<String, Object>>> retrievals(
            @RequestParam(required = false) String skipReason,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(service.pageRetrievals(skipReason, keyword, page, size));
    }

    /* ==================================================================== 事实快照 */

    /**
     * 快照模板清单（D12 的 B 形态）。
     *
     * <p>这是**评审入口**：想确认"灵思会自动把哪些业务数据写进知识库"，看这一个接口即可 ——
     * 每个模板都写明收什么、什么可见范围、为什么。
     */
    @GetMapping("/snapshot-specs")
    @PreAuthorize("hasAuthority('kb:read')")
    public Result<List<Map<String, Object>>> snapshotSpecs() {
        return Result.ok(service.snapshotSpecs());
    }

    /** 已生成的事实快照（带数据截止时间；内容即一段聚合叙述） */
    @GetMapping("/snapshots")
    @PreAuthorize("hasAuthority('kb:read')")
    public Result<PageResult<Map<String, Object>>> snapshots(
            @RequestParam(required = false) String specCode,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.ok(service.pageSnapshots(specCode, keyword, page, size));
    }

    /**
     * 手工重算全部快照（kb:document:manage）。
     *
     * <p>日常由定时任务按日生成；这里用于"刚录完一批人，想立刻能问到"的场景。
     * 生成完顺带跑保留策略清理，避免历史版本堆积后互相竞争检索配额。
     */
    @PostMapping("/snapshots/refresh")
    @PreAuthorize("hasAuthority('kb:index:manage')")
    public Result<Map<String, Object>> refreshSnapshots() {
        return Result.ok(service.refreshSnapshots());
    }
}
