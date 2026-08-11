# 活动内推文管理（对接秀米+公众号发布流程）Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **并行编排（Workflow）：** 本计划由两个互不冲突的并发 agent 在独立 worktree 中执行：
> - **Agent A（后端）**：Task 1-7，所有 `pams-backend` 改动。
> - **Agent B（前端）**：Task 8-10，所有 `pams-web` 改动。
> - 两个 agent 文件集**完全不相交**，可并行；完成后合入 main 并跑全量测试/构建。
> - 设计文档：`docs/superpowers/specs/2026-08-11-activity-article-workflow-design.md`

**Goal:** 让推文管理贴合「秀米排版→长图截图→审核→公众号发布→归档」的真实流程：推文作为活动子模块管理，长图作为审核载体，发布后回填公众号链接与阅读/在看数据，节点通知 + 每日截止提醒。

**Architecture:** 改造现有 `article` 表（加字段，不新建表）+ 加 `APPROVED`（待发布）状态；`ArticleService` 拆出 publish/stats 方法并发布事件；新增 4 个通知事件类 + 4 个 handler + 每日定时任务扫描截止。前端在活动详情页新增「推文」Tab（`ActivityArticlesTab` 组件），现有「推文管理」页改造为跨活动聚合视图。

**Tech Stack:** Spring Boot 4 / JPA / Flyway（MySQL，测试 H2 MySQL 模式）；React 18 + Vite + TS + AntD 5。

## Global Constraints

- **中文 UI 文案**：所有新增用户可见文案用中文。
- **命名通用化**：一律用「推文」/`Article`，不用部门窄词。
- **迁移风格**：V9 同款——每列独立 `ALTER TABLE article ADD ...`，兼容 H2 MySQL 模式；迁移文件放 `pams-backend/src/main/resources/db/migration/`，命名 `V12__article_workflow.sql`。
- **实体**：`Article` 用 Lombok `@Data`，字段直接加私有成员即可（getter/setter 自动生成）。
- **状态枚举顺序**：`DRAFT, PENDING, APPROVED, PUBLISHED, REJECTED`（`Article.ArticleStatus`）。
- **事件驱动通知**：业务代码只 `eventPublisher.publishEvent(...)`，handler 在 `NotificationEventListener`；`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)`。
- **测试**：Mockito 纯单测，无 Spring 上下文；断言用 AssertJ；`ArticleServiceTest` 用 `new ArticleService(repo)` 单参构造。
- **权限角色码**：`MEDIA_LEADER`(新媒体部长) / `TEACHER` / `DIRECTOR`；`LoginUser` 有 `roleCode`/`roleLevel`/`id`。leader = `MEDIA_LEADER`/`TEACHER`/`DIRECTOR` 之一。
- **构建验证**：后端 `mvn -pl pams-backend test`；前端 `npm run build`（`tsc -b && vite build`）。
- **文件上传**：前端 `uploadFile(file, bizType?)` 走 `POST /api/files/upload` 返回 `FileRec{id,path,...}`；展示/回填 URL 用 `/api/files/{id}/download`（公开下载地址，`downloadUrl(id)`）。

---

## Task 1（Agent A）：V12 迁移 + Article 实体字段

**Files:**
- Create: `pams-backend/src/main/resources/db/migration/V12__article_workflow.sql`
- Modify: `pams-backend/src/main/java/com/pams/module/content/entity/Article.java`

**Interfaces:**
- Produces: `Article` 新增字段 `imageUrls(String)` / `deadline(LocalDateTime)` / `wxUrl(String)` / `readCount(Integer)` / `likeCount(Integer)` / `deadlineRemindedAt(LocalDateTime)`；枚举新增 `APPROVED`。

- [ ] **Step 1: 建迁移文件**

`V12__article_workflow.sql`：
```sql
-- ===================== 活动内推文管理（对接秀米+公众号发布流程） =====================
ALTER TABLE article ADD image_urls TEXT NULL COMMENT '长图截图 URL 列表（JSON 数组字符串）';
ALTER TABLE article ADD deadline DATETIME NULL COMMENT '任务截止时间';
ALTER TABLE article ADD wx_url VARCHAR(500) NULL COMMENT '公众号发布链接';
ALTER TABLE article ADD read_count INT NOT NULL DEFAULT 0 COMMENT '阅读量';
ALTER TABLE article ADD like_count INT NOT NULL DEFAULT 0 COMMENT '在看数';
ALTER TABLE article ADD deadline_reminded_at DATETIME NULL COMMENT '截止提醒去重（最近一次提醒时间）';
CREATE INDEX idx_article_activity ON article(activity_id);
```

- [ ] **Step 2: 改 Article 实体**

在 `Article.java` 的 `ArticleStatus` 枚举加 `APPROVED`（放在 `PENDING` 与 `PUBLISHED` 之间）；新增字段：
```java
@Column(columnDefinition = "TEXT")
private String imageUrls;
private LocalDateTime deadline;
private String wxUrl;
private Integer readCount;
private Integer likeCount;
private LocalDateTime deadlineRemindedAt;
```
需 `import java.time.LocalDateTime;`（已存在）。

- [ ] **Step 3: 编译验证**

Run: `mvn -pl pams-backend compile -q`
Expected: BUILD SUCCESS，无编译错误。

- [ ] **Step 4: Commit**

```bash
git add pams-backend/src/main/resources/db/migration/V12__article_workflow.sql pams-backend/src/main/java/com/pams/module/content/entity/Article.java
git commit -m "feat(content): article 表加长图/截止/公众号链接/阅读数据字段 + APPROVED 状态"
```

## Task 2（Agent A）：DTO 扩展

**Files:**
- Modify: `pams-backend/src/main/java/com/pams/module/content/dto/ArticleRequest.java`
- Create: `pams-backend/src/main/java/com/pams/module/content/dto/PublishRequest.java`
- Create: `pams-backend/src/main/java/com/pams/module/content/dto/StatsRequest.java`

**Interfaces:**
- Consumes: Task 1 的 `Article` 字段。
- Produces: `ArticleRequest`（新增 `activityId` 必填、`deadline`、`authorId`、`imageUrls(List<String>)`）、`PublishRequest{wxUrl}`、`StatsRequest{readCount, likeCount}`。

- [ ] **Step 1: 改 ArticleRequest**

```java
package com.pams.module.content.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.time.LocalDateTime;
import java.util.List;

@Data
public class ArticleRequest {
    @NotBlank(message = "推文标题不能为空")
    private String title;
    private String summary;
    private String content;
    private String coverUrl;
    @NotNull(message = "推文必须关联活动")
    private Long activityId;
    @NotNull(message = "请设置任务截止时间")
    private LocalDateTime deadline;
    private Long authorId;               // 负责人
    private List<String> imageUrls;      // 长图 URL 列表
    private String articleType;
}
```

- [ ] **Step 2: 建 PublishRequest**

```java
package com.pams.module.content.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class PublishRequest {
    @NotBlank(message = "公众号链接不能为空")
    private String wxUrl;
}
```

- [ ] **Step 3: 建 StatsRequest**

```java
package com.pams.module.content.dto;

import lombok.Data;

@Data
public class StatsRequest {
    private Integer readCount;
    private Integer likeCount;
}
```

- [ ] **Step 4: Commit**

```bash
git add pams-backend/src/main/java/com/pams/module/content/dto/ArticleRequest.java pams-backend/src/main/java/com/pams/module/content/dto/PublishRequest.java pams-backend/src/main/java/com/pams/module/content/dto/StatsRequest.java
git commit -m "feat(content): ArticleRequest 增必填 activityId/deadline/authorId/imageUrls + Publish/Stats DTO"
```

## Task 3（Agent A）：ArticleService 状态流重构 + 新方法

**Files:**
- Modify: `pams-backend/src/main/java/com/pams/module/content/service/ArticleService.java`
- Modify: `pams-backend/src/main/java/com/pams/module/content/repository/ArticleRepository.java`

**Interfaces:**
- Consumes: Task 1/2 的实体字段与 DTO。
- Produces: `page(status,type,keyword,activityId,page,size)`（VO 含 `activityName`/`imageUrls`/`deadline`/`wxUrl`/`readCount`/`likeCount`）；`create(authorId 当前用户, req)`（负责人=req.authorId，发 `ArticleAssignedEvent`）；`update(id,req,currentUserId,isLeader)`（DRAFT/REJECTED 可改，leader 或负责人）；`submit(id,submitterId)`（DRAFT/REJECTED→PENDING，发 `ContentUploadedEvent`）；`review(id,approved,comment,reviewerId)`（PENDING→APPROVED/REJECTED，发 `ArticleReviewedEvent`）；`publish(id,req,currentUserId,isLeader)`（APPROVED→PUBLISHED，发 `ArticlePublishedEvent`）；`updateStats(id,req,currentUserId,isLeader)`（仅 PUBLISHED）；`delete(id)`。
- 依赖 Task 5 的事件类（Task 1-7 由同一 agent 完成，无跨 agent 编译问题）。

- [ ] **Step 1: ArticleRepository 加查询方法**

```java
package com.pams.module.content.repository;

import com.pams.module.content.entity.Article;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface ArticleRepository extends JpaRepository<Article, Long>,
        JpaSpecificationExecutor<Article> {
    @Query("SELECT a FROM Article a WHERE a.deleted = 0 AND a.status <> 'PUBLISHED' " +
           "AND a.deadline IS NOT NULL AND a.deadline <= :threshold")
    List<Article> findOverdue(@Param("threshold") LocalDateTime threshold);
}
```

- [ ] **Step 2: ArticleService 全量替换**

整文件替换为（保留单参构造给测试，双参构造加 `ActivityRepository`；`toVo` 解析 `imageUrls` 并带 `activityName`）：

```java
package com.pams.module.content.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.content.dto.ArticleRequest;
import com.pams.module.content.dto.PublishRequest;
import com.pams.module.content.dto.StatsRequest;
import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.module.notification.event.ArticleAssignedEvent;
import com.pams.module.notification.event.ArticlePublishedEvent;
import com.pams.module.notification.event.ArticleReviewedEvent;
import com.pams.module.notification.event.ContentUploadedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ArticleService {
    private final ArticleRepository repository;
    private final ActivityRepository activityRepository;
    private final ApplicationEventPublisher eventPublisher;
    private static final ObjectMapper OM = new ObjectMapper();

    public ArticleService(ArticleRepository repository) {
        this(repository, null, null);
    }

    @Autowired
    public ArticleService(ArticleRepository repository, ActivityRepository activityRepository,
                          ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.activityRepository = activityRepository;
        this.eventPublisher = eventPublisher;
    }

    public static boolean isLeader(String roleCode) {
        return "MEDIA_LEADER".equals(roleCode) || "TEACHER".equals(roleCode) || "DIRECTOR".equals(roleCode);
    }

    public PageResult<Map<String, Object>> page(String status, String type, String keyword, Long activityId,
                                                int page, int size) {
        Page<Article> p = repository.findAll((root, q, cb) -> {
            var preds = new ArrayList<jakarta.persistence.criteria.Predicate>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                preds.add(cb.or(cb.like(root.get("title"), like), cb.like(root.get("summary"), like)));
            }
            if (status != null && !status.isBlank()) {
                preds.add(cb.equal(root.get("status"), parseEnum(Article.ArticleStatus.class, status)));
            }
            if (type != null && !type.isBlank()) {
                preds.add(cb.equal(root.get("articleType"), parseEnum(Article.ArticleType.class, type)));
            }
            if (activityId != null) {
                preds.add(cb.equal(root.get("activityId"), activityId));
            }
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(this::toVo).toList());
        r.setTotal(p.getTotalElements());
        r.setCurrent(page);
        r.setSize(size);
        return r;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        return Enum.valueOf(type, value);
    }

    private Map<String, Object> toVo(Article a) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", a.getId());
        vo.put("title", a.getTitle());
        vo.put("summary", a.getSummary() == null ? "" : a.getSummary());
        vo.put("content", a.getContent() == null ? "" : a.getContent());
        vo.put("coverUrl", a.getCoverUrl() == null ? "" : a.getCoverUrl());
        vo.put("activityId", a.getActivityId());
        vo.put("activityName", activityNameOf(a.getActivityId()));
        vo.put("articleType", a.getArticleType() == null ? "REPORT" : a.getArticleType().name());
        vo.put("status", a.getStatus() == null ? "DRAFT" : a.getStatus().name());
        vo.put("authorId", a.getAuthorId());
        vo.put("reviewerId", a.getReviewerId());
        vo.put("reviewComment", a.getReviewComment() == null ? "" : a.getReviewComment());
        vo.put("imageUrls", parseImageUrls(a.getImageUrls()));
        vo.put("deadline", a.getDeadline());
        vo.put("wxUrl", a.getWxUrl() == null ? "" : a.getWxUrl());
        vo.put("readCount", a.getReadCount() == null ? 0 : a.getReadCount());
        vo.put("likeCount", a.getLikeCount() == null ? 0 : a.getLikeCount());
        vo.put("publishTime", a.getPublishTime());
        vo.put("createdAt", a.getCreatedAt());
        vo.put("updatedAt", a.getUpdatedAt());
        return vo;
    }

    private String activityNameOf(Long activityId) {
        if (activityId == null || activityRepository == null) return "";
        return activityRepository.findById(activityId).map(a -> a.getName()).orElse("");
    }

    private List<String> parseImageUrls(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return OM.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return new ArrayList<>();
        }
    }

    public Article getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2001, "推文不存在"));
    }

    @Transactional
    public Article create(Long creatorId, ArticleRequest req) {
        if (req.getAuthorId() == null) {
            throw new BizException(2004, "请指定推文负责人");
        }
        Article a = new Article();
        a.setStatus(Article.ArticleStatus.DRAFT);
        a.setAuthorId(req.getAuthorId());       // 负责人由创建者指定
        a.setActivityId(req.getActivityId());
        a.setDeadline(req.getDeadline());
        a.setReadCount(0);
        a.setLikeCount(0);
        apply(a, req);
        a.setDeleted(0);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        Article saved = repository.save(a);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ArticleAssignedEvent(
                    saved.getId(), saved.getActivityId(), saved.getTitle(), saved.getAuthorId(), creatorId));
        }
        return saved;
    }

    @Transactional
    public void update(Long id, ArticleRequest req, Long currentUserId, boolean leader) {
        Article a = getEntity(id);
        if (a.getStatus() != Article.ArticleStatus.DRAFT
                && a.getStatus() != Article.ArticleStatus.REJECTED) {
            throw new BizException(2003, "仅草稿或驳回状态的推文可编辑");
        }
        if (!leader && (a.getAuthorId() == null || !a.getAuthorId().equals(currentUserId))) {
            throw new BizException(2002, "无权编辑该推文");
        }
        if (req.getDeadline() != null) a.setDeadline(req.getDeadline());
        if (req.getAuthorId() != null && leader) a.setAuthorId(req.getAuthorId());
        a.setActivityId(req.getActivityId() != null ? req.getActivityId() : a.getActivityId());
        apply(a, req);
        repository.save(a);
    }

    @Transactional
    public void submit(Long id, Long submitterId) {
        Article a = getEntity(id);
        if (a.getStatus() != Article.ArticleStatus.DRAFT
                && a.getStatus() != Article.ArticleStatus.REJECTED) {
            throw new BizException(2005, "当前状态不可提交审核");
        }
        a.setStatus(Article.ArticleStatus.PENDING);
        a.setUpdatedAt(LocalDateTime.now());
        repository.save(a);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ContentUploadedEvent(
                    a.getId(), a.getActivityId(), a.getTitle(), "ARTICLE", submitterId));
        }
    }

    @Transactional
    public void review(Long id, boolean approved, String comment, Long reviewerId) {
        Article a = getEntity(id);
        if (a.getStatus() != Article.ArticleStatus.PENDING) {
            throw new BizException(2006, "仅待审核状态的推文可审核");
        }
        a.setStatus(approved ? Article.ArticleStatus.APPROVED : Article.ArticleStatus.REJECTED);
        a.setReviewerId(reviewerId);
        a.setReviewComment(comment);
        a.setUpdatedAt(LocalDateTime.now());
        repository.save(a);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ArticleReviewedEvent(
                    a.getId(), a.getActivityId(), a.getTitle(), approved, comment, a.getAuthorId(), reviewerId));
        }
    }

    @Transactional
    public void publish(Long id, PublishRequest req, Long currentUserId, boolean leader) {
        Article a = getEntity(id);
        if (a.getStatus() != Article.ArticleStatus.APPROVED) {
            throw new BizException(2007, "仅审核通过（待发布）的推文可标记发布");
        }
        if (!leader && !a.getAuthorId().equals(currentUserId)) {
            throw new BizException(2002, "无权发布该推文");
        }
        a.setStatus(Article.ArticleStatus.PUBLISHED);
        a.setWxUrl(req.getWxUrl());
        a.setPublishTime(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        repository.save(a);
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new ArticlePublishedEvent(
                    a.getId(), a.getActivityId(), a.getTitle(), currentUserId));
        }
    }

    @Transactional
    public void updateStats(Long id, StatsRequest req, Long currentUserId, boolean leader) {
        Article a = getEntity(id);
        if (a.getStatus() != Article.ArticleStatus.PUBLISHED) {
            throw new BizException(2008, "仅已发布的推文可更新阅读数据");
        }
        if (!leader && !a.getAuthorId().equals(currentUserId)) {
            throw new BizException(2002, "无权更新该推文数据");
        }
        if (req.getReadCount() != null) a.setReadCount(req.getReadCount());
        if (req.getLikeCount() != null) a.setLikeCount(req.getLikeCount());
        a.setUpdatedAt(LocalDateTime.now());
        repository.save(a);
    }

    @Transactional
    public void delete(Long id) {
        Article a = getEntity(id);
        a.setDeleted(1);
        a.setUpdatedAt(LocalDateTime.now());
        repository.save(a);
    }

    private void apply(Article a, ArticleRequest req) {
        a.setTitle(req.getTitle());
        a.setSummary(req.getSummary());
        a.setContent(req.getContent());
        a.setCoverUrl(req.getCoverUrl());
        if (req.getImageUrls() != null) {
            try {
                a.setImageUrls(OM.writeValueAsString(req.getImageUrls()));
            } catch (Exception e) {
                a.setImageUrls("[]");
            }
        }
        if (req.getArticleType() != null && !req.getArticleType().isBlank()) {
            a.setArticleType(Article.ArticleType.valueOf(req.getArticleType()));
        }
        a.setUpdatedAt(LocalDateTime.now());
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `mvn -pl pams-backend compile -q`（Task 5 事件类未建会报错，若报找不到 `ArticleAssignedEvent` 等，先跳过——Task 5 建完再编译）
Expected: 若缺事件类，编译失败属预期；Task 5 完成后整体编译通过。

- [ ] **Step 4: Commit**

```bash
git add pams-backend/src/main/java/com/pams/module/content/service/ArticleService.java pams-backend/src/main/java/com/pams/module/content/repository/ArticleRepository.java
git commit -m "feat(content): ArticleService 重构状态流（APPROVED）+ publish/stats/update 权限 + findOverdue"
```

## Task 4（Agent A）：ArticleController 新端点

**Files:**
- Modify: `pams-backend/src/main/java/com/pams/module/content/controller/ArticleController.java`

**Interfaces:**
- Consumes: Task 2/3 的 DTO 与方法。
- Produces: `GET /api/articles` 加 `activityId` 参数；`PUT /{id}/publish`、`PUT /{id}/stats`；`PUT /{id}`、`PUT /{id}/submit`、`PUT /{id}/review` 传当前用户与 leader 标志。

- [ ] **Step 1: 全量替换 Controller**

```java
package com.pams.module.content.controller;

import com.pams.common.PageResult;
import com.pams.common.Result;
import com.pams.module.content.dto.ArticleRequest;
import com.pams.module.content.dto.PublishRequest;
import com.pams.module.content.dto.ReviewRequest;
import com.pams.module.content.dto.StatsRequest;
import com.pams.module.content.entity.Article;
import com.pams.module.content.service.ArticleService;
import com.pams.security.LoginUser;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/articles")
public class ArticleController {
    private final ArticleService service;
    public ArticleController(ArticleService service) { this.service = service; }

    @GetMapping
    public Result<PageResult<Map<String, Object>>> page(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) Long activityId,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "10") int size) {
        return Result.ok(service.page(status, type, keyword, activityId, page, size));
    }

    @PreAuthorize("hasRole('MEDIA_LEADER') or hasAnyRole('TEACHER','DIRECTOR')")
    @PostMapping
    public Result<Article> create(@Valid @RequestBody ArticleRequest req,
                                  @AuthenticationPrincipal LoginUser current) {
        return Result.ok(service.create(current == null ? null : current.getId(), req));
    }

    @PutMapping("/{id}")
    public Result<Void> update(@PathVariable Long id, @Valid @RequestBody ArticleRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        service.update(id, req, current.getId(), current != null && ArticleService.isLeader(current.getRoleCode()));
        return Result.ok();
    }

    @PutMapping("/{id}/submit")
    public Result<Void> submit(@PathVariable Long id, @AuthenticationPrincipal LoginUser current) {
        service.submit(id, current == null ? null : current.getId());
        return Result.ok();
    }

    @PreAuthorize("hasRole('MEDIA_LEADER') or hasAnyRole('TEACHER','DIRECTOR')")
    @PutMapping("/{id}/review")
    public Result<Void> review(@PathVariable Long id, @Valid @RequestBody ReviewRequest req,
                               @AuthenticationPrincipal LoginUser current) {
        boolean approved = req.getApproved() != null && req.getApproved();
        service.review(id, approved, req.getComment(), current == null ? null : current.getId());
        return Result.ok();
    }

    @PutMapping("/{id}/publish")
    public Result<Void> publish(@PathVariable Long id, @Valid @RequestBody PublishRequest req,
                                @AuthenticationPrincipal LoginUser current) {
        service.publish(id, req, current.getId(), current != null && ArticleService.isLeader(current.getRoleCode()));
        return Result.ok();
    }

    @PutMapping("/{id}/stats")
    public Result<Void> updateStats(@PathVariable Long id, @Valid @RequestBody StatsRequest req,
                                    @AuthenticationPrincipal LoginUser current) {
        service.updateStats(id, req, current.getId(), current != null && ArticleService.isLeader(current.getRoleCode()));
        return Result.ok();
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        service.delete(id);
        return Result.ok();
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add pams-backend/src/main/java/com/pams/module/content/controller/ArticleController.java
git commit -m "feat(content): ArticleController 增 activityId 筛选 + publish/stats 端点 + 权限"
```

## Task 5（Agent A）：通知事件类 + handler + NotificationType

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/notification/event/ArticleAssignedEvent.java`
- Create: `pams-backend/src/main/java/com/pams/module/notification/event/ArticleReviewedEvent.java`
- Create: `pams-backend/src/main/java/com/pams/module/notification/event/ArticlePublishedEvent.java`
- Create: `pams-backend/src/main/java/com/pams/module/notification/event/ArticleDeadlineReminderEvent.java`
- Modify: `pams-backend/src/main/java/com/pams/module/notification/entity/NotificationType.java`
- Modify: `pams-backend/src/main/java/com/pams/module/notification/listener/NotificationEventListener.java`

**Interfaces:**
- Consumes: Task 3 发布的 4 个事件。
- Produces: `NotificationType` 新增 5 枚举；`NotificationEventListener` 新增 4 个 handler。
- **与设计文档的偏差**：设计文档原列出 `GET /api/articles/overdue?days=` 端点，本计划改为定时任务（Task 6）直接经 `ArticleRepository.findOverdue` 查询，不再暴露公开端点（前端不需要，避免无用接口）。

- [ ] **Step 1: 建 4 个事件类**（仿 `ContentUploadedEvent` 风格，getter 手写）

`ArticleAssignedEvent`（完整示例）：
```java
package com.pams.module.notification.event;

public class ArticleAssignedEvent {
    private final Long articleId;
    private final Long activityId;
    private final String title;
    private final Long assigneeId;
    private final Long creatorId;

    public ArticleAssignedEvent(Long articleId, Long activityId, String title,
                                Long assigneeId, Long creatorId) {
        this.articleId = articleId;
        this.activityId = activityId;
        this.title = title;
        this.assigneeId = assigneeId;
        this.creatorId = creatorId;
    }

    public Long getArticleId() { return articleId; }
    public Long getActivityId() { return activityId; }
    public String getTitle() { return title; }
    public Long getAssigneeId() { return assigneeId; }
    public Long getCreatorId() { return creatorId; }
}
```

`ArticleReviewedEvent`：字段 `articleId, activityId, title, approved(boolean), comment, authorId, reviewerId`（同款 getter，`boolean isApproved()`）。
`ArticlePublishedEvent`：字段 `articleId, activityId, title, publisherId`。
`ArticleDeadlineReminderEvent`：字段 `articleId, activityId, title, authorId, deadline(LocalDateTime)`（`import java.time.LocalDateTime;`）。

- [ ] **Step 2: NotificationType 加枚举**

在枚举末尾追加：`ARTICLE_ASSIGNED, ARTICLE_APPROVED, ARTICLE_REJECTED, ARTICLE_PUBLISHED, ARTICLE_DEADLINE_REMINDER`。

- [ ] **Step 3: NotificationEventListener 加 4 个 handler**

在类内（现有 handler 之后、`broadcastToRoles` 之前）新增：
```java
@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleArticleAssigned(ArticleAssignedEvent e) {
    String activityName = getActivityName(e.getActivityId());
    userRepo.findById(e.getAssigneeId()).ifPresent(u -> {
        notificationService.createAndSave(NotificationType.ARTICLE_ASSIGNED,
            "推文任务已指派",
            "你负责撰写推文《" + e.getTitle() + "》（活动：" + activityName + "），请按时完成",
            "ARTICLE", e.getArticleId(), e.getCreatorId(), u.getId(), null, null);
        pushToUser(u);
    });
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleArticleReviewed(ArticleReviewedEvent e) {
    String activityName = getActivityName(e.getActivityId());
    NotificationType type = e.isApproved() ? NotificationType.ARTICLE_APPROVED : NotificationType.ARTICLE_REJECTED;
    String title = e.isApproved() ? "推文审核通过" : "推文被驳回";
    String content = "推文《" + e.getTitle() + "》（活动：" + activityName + "）"
        + (e.isApproved() ? "已通过审核，请发布" : "被驳回" + (e.getComment() != null ? "，原因：" + e.getComment() : ""));
    userRepo.findById(e.getAuthorId()).ifPresent(u -> {
        notificationService.createAndSave(type, title, content, "ARTICLE", e.getArticleId(),
            e.getReviewerId(), u.getId(), null, null);
        pushToUser(u);
    });
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleArticlePublished(ArticlePublishedEvent e) {
    String activityName = getActivityName(e.getActivityId());
    broadcastToRoles(NotificationType.ARTICLE_PUBLISHED, "推文已发布",
        "推文《" + e.getTitle() + "》（活动：" + activityName + "）已发布",
        "ARTICLE", e.getArticleId(), e.getPublisherId(), ALL_LEADER_ROLES);
}

@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
public void handleArticleDeadlineReminder(ArticleDeadlineReminderEvent e) {
    String activityName = getActivityName(e.getActivityId());
    userRepo.findById(e.getAuthorId()).ifPresent(u -> {
        notificationService.createAndSave(NotificationType.ARTICLE_DEADLINE_REMINDER,
            "推文截止提醒",
            "推文《" + e.getTitle() + "》截止时间 " + e.getDeadline() + "（活动：" + activityName + "），请尽快完成",
            "ARTICLE", e.getArticleId(), null, u.getId(), null, null);
        pushToUser(u);
    });
}
```

- [ ] **Step 4: 整体编译**

Run: `mvn -pl pams-backend compile -q`
Expected: BUILD SUCCESS（Task 3 引用的 4 个事件类此时已存在）。

- [ ] **Step 5: Commit**

```bash
git add pams-backend/src/main/java/com/pams/module/notification/
git commit -m "feat(notification): 推文指派/审核/发布/截止提醒 4 事件 + handler + NotificationType 扩 5 枚举"
```

## Task 6（Agent A）：每日截止提醒定时任务 + @EnableScheduling

**Files:**
- Create: `pams-backend/src/main/java/com/pams/module/content/task/ArticleDeadlineTask.java`
- Modify: `pams-backend/src/main/java/com/pams/PartyAffairsManagementSystemApplication.java`

**Interfaces:**
- Consumes: Task 1 的 `deadlineRemindedAt`、Task 5 的 `ArticleDeadlineReminderEvent`、`ArticleRepository.findOverdue`。

- [ ] **Step 1: 建定时任务类**

```java
package com.pams.module.content.task;

import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.module.notification.event.ArticleDeadlineReminderEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@Component
public class ArticleDeadlineTask {
    private static final Logger log = LoggerFactory.getLogger(ArticleDeadlineTask.class);
    private final ArticleRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public ArticleDeadlineTask(ArticleRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    /** 每天 8:30 扫描：未发布且 3 天内到期的推文，向负责人发截止提醒（每天一次，去重） */
    @Scheduled(cron = "0 30 8 * * ?")
    public void remindOverdue() {
        LocalDateTime now = LocalDateTime.now();
        LocalDate today = now.toLocalDate();
        List<Article> candidates = repository.findOverdue(now.plusDays(3));
        int reminded = 0;
        for (Article a : candidates) {
            if (a.getDeadlineRemindedAt() != null
                    && a.getDeadlineRemindedAt().toLocalDate().equals(today)) {
                continue; // 今天已提醒过
            }
            if (a.getAuthorId() != null) {
                eventPublisher.publishEvent(new ArticleDeadlineReminderEvent(
                        a.getId(), a.getActivityId(), a.getTitle(), a.getAuthorId(), a.getDeadline()));
                reminded++;
            }
            a.setDeadlineRemindedAt(now);
            repository.save(a);
        }
        if (reminded > 0) {
            log.info("ArticleDeadlineTask 提醒 {} 条截止推文", reminded);
        }
    }
}
```

- [ ] **Step 2: 主应用加 @EnableScheduling**

`PartyAffairsManagementSystemApplication.java` 加 `import org.springframework.scheduling.annotation.EnableScheduling;` 与类注解 `@EnableScheduling`。

- [ ] **Step 3: 编译验证**

Run: `mvn -pl pams-backend compile -q`
Expected: BUILD SUCCESS。

- [ ] **Step 4: Commit**

```bash
git add pams-backend/src/main/java/com/pams/module/content/task/ArticleDeadlineTask.java pams-backend/src/main/java/com/pams/PartyAffairsManagementSystemApplication.java
git commit -m "feat(content): 每日 8:30 扫临近/过期推文向负责人发截止提醒 + @EnableScheduling"
```

## Task 7（Agent A）：ArticleServiceTest 更新与新增

**Files:**
- Modify: `pams-backend/src/test/java/com/pams/module/content/ArticleServiceTest.java`

**Interfaces:**
- Consumes: Task 3 的 `ArticleService`（单参构造 `new ArticleService(repo)`；`eventPublisher` 为 null 时事件不触发）。

- [ ] **Step 1: 更新 review 用例 + 新增用例**

现有 `review_approve_publishes` 改为断言 **APPROVED**（不再是 PUBLISHED、不自动写 publishTime）：
```java
@Test
void review_approve_moves_to_approved() {
    Article a = new Article();
    a.setId(1L);
    a.setStatus(Article.ArticleStatus.PENDING);
    when(repo.findById(1L)).thenReturn(Optional.of(a));
    service.review(1L, true, "ok", 100L);
    assertThat(a.getStatus()).isEqualTo(Article.ArticleStatus.APPROVED);
    assertThat(a.getReviewerId()).isEqualTo(100L);
    assertThat(a.getReviewComment()).isEqualTo("ok");
    assertThat(a.getPublishTime()).isNull();
    verify(repo).save(a);
}

@Test
void review_reject_moves_to_rejected() {
    Article a = new Article();
    a.setId(2L);
    a.setStatus(Article.ArticleStatus.PENDING);
    when(repo.findById(2L)).thenReturn(Optional.of(a));
    service.review(2L, false, "改标题", 100L);
    assertThat(a.getStatus()).isEqualTo(Article.ArticleStatus.REJECTED);
    assertThat(a.getReviewComment()).isEqualTo("改标题");
}

@Test
void publish_requires_approved_status() {
    Article a = new Article();
    a.setId(3L);
    a.setStatus(Article.ArticleStatus.DRAFT);
    a.setAuthorId(50L);
    when(repo.findById(3L)).thenReturn(Optional.of(a));
    com.pams.module.content.dto.PublishRequest req = new com.pams.module.content.dto.PublishRequest();
    req.setWxUrl("https://mp.weixin.qq.com/s/abc");
    assertThatThrownBy(() -> service.publish(3L, req, 50L, false))
            .isInstanceOf(BizException.class);
}

@Test
void publish_sets_published_and_wx_url() {
    Article a = new Article();
    a.setId(4L);
    a.setStatus(Article.ArticleStatus.APPROVED);
    a.setAuthorId(50L);
    when(repo.findById(4L)).thenReturn(Optional.of(a));
    com.pams.module.content.dto.PublishRequest req = new com.pams.module.content.dto.PublishRequest();
    req.setWxUrl("https://mp.weixin.qq.com/s/abc");
    service.publish(4L, req, 50L, false);
    assertThat(a.getStatus()).isEqualTo(Article.ArticleStatus.PUBLISHED);
    assertThat(a.getWxUrl()).isEqualTo("https://mp.weixin.qq.com/s/abc");
    assertThat(a.getPublishTime()).isNotNull();
}

@Test
void update_only_allows_draft_or_rejected() {
    Article a = new Article();
    a.setId(5L);
    a.setStatus(Article.ArticleStatus.PENDING);
    a.setAuthorId(50L);
    when(repo.findById(5L)).thenReturn(Optional.of(a));
    com.pams.module.content.dto.ArticleRequest req = new com.pams.module.content.dto.ArticleRequest();
    req.setTitle("新标题");
    assertThatThrownBy(() -> service.update(5L, req, 50L, false))
            .isInstanceOf(BizException.class);
}

@Test
void update_denies_non_author_non_leader() {
    Article a = new Article();
    a.setId(6L);
    a.setStatus(Article.ArticleStatus.DRAFT);
    a.setAuthorId(50L);
    when(repo.findById(6L)).thenReturn(Optional.of(a));
    com.pams.module.content.dto.ArticleRequest req = new com.pams.module.content.dto.ArticleRequest();
    req.setTitle("新标题");
    assertThatThrownBy(() -> service.update(6L, req, 99L, false))
            .isInstanceOf(BizException.class);
}

@Test
void create_requires_author_id() {
    com.pams.module.content.dto.ArticleRequest req = new com.pams.module.content.dto.ArticleRequest();
    req.setTitle("预热");
    req.setActivityId(1L);
    req.setDeadline(java.time.LocalDateTime.now().plusDays(1));
    assertThatThrownBy(() -> service.create(10L, req))
            .isInstanceOf(BizException.class);
}
```

- [ ] **Step 2: 跑测试**

Run: `mvn -pl pams-backend test -Dtest=ArticleServiceTest`
Expected: 全部 PASS。

- [ ] **Step 3: 跑全量后端测试**

Run: `mvn -pl pams-backend test -q`
Expected: 全绿（原 183+ 测试不受影响）。

- [ ] **Step 4: Commit**

```bash
git add pams-backend/src/test/java/com/pams/module/content/ArticleServiceTest.java
git commit -m "test(content): ArticleService 状态流/publish/update 权限用例 + 原 review 用例改为 APPROVED"
```

---

## Task 8（Agent B）：前端 api/article.ts 扩展

**Files:**
- Modify: `pams-web/src/api/article.ts`

**Interfaces:**
- Produces: `ArticleVO` 增字段（`activityName`/`imageUrls: string[]`/`deadline`/`wxUrl`/`readCount`/`likeCount`，status 增 `APPROVED`）；`ArticleSave` 增 `activityId`/`authorId`/`deadline`/`imageUrls`；`ARTICLE_STATUS_MAP` 增 `APPROVED:'待发布'`；`listArticles` 参数增 `activityId`；新函数 `publishArticle`/`updateArticleStats`。

- [ ] **Step 1: 全量替换 api/article.ts**

```ts
import { get, post, put, del } from './http'
import type { PageResult } from './types'

export interface ArticleVO {
  id: number
  title: string
  summary: string
  content: string
  coverUrl: string
  activityId: number | null
  activityName: string
  articleType: 'PREHEAT' | 'REPORT' | 'VIDEO'
  status: 'DRAFT' | 'PENDING' | 'APPROVED' | 'PUBLISHED' | 'REJECTED'
  authorId: number | null
  reviewerId: number | null
  reviewComment: string
  imageUrls: string[]
  deadline: string | null
  wxUrl: string | null
  readCount: number
  likeCount: number
  publishTime: string | null
  createdAt: string | null
  updatedAt: string | null
}

export interface ArticleSave {
  title: string
  summary?: string
  content?: string
  coverUrl?: string
  activityId: number
  authorId?: number
  deadline?: string
  imageUrls?: string[]
  articleType?: string
}

export const ARTICLE_STATUS_MAP: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待审',
  APPROVED: '待发布',
  PUBLISHED: '已发布',
  REJECTED: '被驳回',
}

export const ARTICLE_STATUS_OPTIONS = Object.entries(ARTICLE_STATUS_MAP).map(([value, label]) => ({ value, label }))

export const ARTICLE_TYPE_MAP: Record<string, string> = {
  PREHEAT: '预热',
  REPORT: '报道',
  VIDEO: '宣传视频',
}

export const ARTICLE_TYPE_OPTIONS = Object.entries(ARTICLE_TYPE_MAP).map(([value, label]) => ({ value, label }))

export const listArticles = (params: {
  status?: string
  type?: string
  keyword?: string
  activityId?: number
  page?: number
  size?: number
}) => get<PageResult<ArticleVO>>('/articles', params)
export const createArticle = (data: ArticleSave) => post<number>('/articles', data)
export const updateArticle = (id: number, data: ArticleSave) => put<void>(`/articles/${id}`, data)
export const submitArticle = (id: number) => put<void>(`/articles/${id}/submit`)
export const reviewArticle = (id: number, approved: boolean, comment?: string) =>
  put<void>(`/articles/${id}/review`, { approved, comment })
export const publishArticle = (id: number, data: { wxUrl: string }) =>
  put<void>(`/articles/${id}/publish`, data)
export const updateArticleStats = (id: number, data: { readCount: number; likeCount: number }) =>
  put<void>(`/articles/${id}/stats`, data)
export const deleteArticle = (id: number) => del<void>(`/articles/${id}`)
```

- [ ] **Step 2: 类型检查**

Run: `npx tsc -b --noEmit`（在 pams-web 目录）
Expected: 无新增类型错误。

- [ ] **Step 3: Commit**

```bash
git add pams-web/src/api/article.ts
git commit -m "feat(web): article api 增 activityId 筛选/APPROVED/发布/阅读数据接口"
```

## Task 9（Agent B）：「推文管理」页改造成跨活动聚合视图

**Files:**
- Modify: `pams-web/src/pages/content/ArticleList.tsx`

**Interfaces:**
- Consumes: Task 8 的 api；`listUsers`（作者/负责人下拉，已用）；`listActivities({page,size})`（活动下拉）。
- Produces: 聚合列表（含所属活动、截止时间、公众号链接、阅读/在看列）+ 撰写/编辑表单补活动/负责人/截止/长图上传字段 + 发布/更新数据操作。

- [ ] **Step 1: 列表列扩展**

在 `columns` 加：
- `所属活动`：`activityName`（`render: (v) => v || '-'`）
- `截止时间`：`deadline`，逾期（deadline < now 且状态非 PUBLISHED）显示红色；`render: (v, r) => <span style={{ color: isOverdue(r) ? 'var(--color-red)' : undefined }}>{v ? dayjs(v).format('YYYY-MM-DD') : '-'}</span>`
- `公众号`：`wxUrl` 可点链接（`<a href target="_blank" rel="noreferrer">打开</a>`）
- `数据`：`阅读 {readCount} / 在看 {likeCount}`

顶部筛选区加「按活动」`Select`（`listActivities({ page: 1, size: 1000 })` 填充，`allowClear`，值存 `activityId` state，变化时 `setPage(1)` 重新拉取）。

- [ ] **Step 2: 表单补字段**

撰写/编辑弹窗（`ArticleFormValues`）补：
- `activityId`：`Select`（活动下拉，`rules: [{ required: true }]`）
- `authorId`：`Select`（用户下拉，`rules: [{ required: true, message: '请指定负责人' }]`；仅编辑时 leader 可改）
- `deadline`：`DatePicker`（`showTime`，默认今天 23:59，`rules: [{ required: true }]`）
- `imageUrls`：`Upload` 多图（`beforeUpload` 返回 false 手动传），用 `uploadFile(file, 'article')` 上传得 `FileRec`，push `/api/files/${id}/download` 到数组；`fileList` 受控展示缩略。

`handleSave` 构造 payload 时：`activityId: values.activityId`、`authorId: values.authorId`、`deadline: dayjs(values.deadline).format('YYYY-MM-DDTHH:mm:ss')`、`imageUrls: values.imageUrls`。创建时 `createArticle(payload)`，编辑时 `updateArticle(id, payload)`。

`openCreate` 仅 leader 可见（`canReview` 已定义，即 `MEDIA_LEADER` 或 `roleLevel>=4`）；页面头部「撰写推文」按钮加 `{canReview && (...)}` 条件。

- [ ] **Step 3: 操作列补发布/更新数据**

- 状态 `APPROVED`：显示「标记发布」按钮 → `PublishModal`（输入公众号链接，确认调 `publishArticle`）。
- 状态 `PUBLISHED`：显示「更新数据」按钮 → `StatsModal`（阅读量/在看数 `InputNumber`，确认调 `updateArticleStats`）。
- 编辑按钮可见条件保持 `DRAFT/REJECTED`；「提交」保持 `DRAFT/REJECTED`。

两个小 modal 复用现有 `GlassModal` 风格（参考 `reviewTarget` modal）。

- [ ] **Step 4: 类型检查 + 构建**

Run: `npx tsc -b --noEmit` 然后 `npm run build`
Expected: 无类型错误，构建成功。

- [ ] **Step 5: Commit**

```bash
git add pams-web/src/pages/content/ArticleList.tsx
git commit -m "feat(web): 推文管理页改造为聚合视图（活动/截止/链接/数据列 + 发布/数据操作）"
```

## Task 10（Agent B）：活动详情「推文」Tab（ActivityArticlesTab + 接线）

**Files:**
- Create: `pams-web/src/pages/activity/ActivityArticlesTab.tsx`
- Modify: `pams-web/src/pages/activity/ActivityDetail.tsx`（import + tabItems 加一项）

**Interfaces:**
- Consumes: Task 8 的 api；`uploadFile`/`downloadUrl` from `@/api/file`；`listUsers` from `@/api/user`；props `{ activityId: number; activity: ActivityVO | undefined }`。
- Produces: 活动内推文卡片列表 + 新建/快捷创建/编辑/上传长图/提交/审核/标记发布/更新数据 全部操作。

- [ ] **Step 1: 建 ActivityArticlesTab.tsx**

组件结构（复用 `GlassCard`/`GlassModal`/`StatusTag`/`PageHeader` 风格）：

- **Props**：`activityId: number`、`activity?: ActivityVO`（用 `startDate`/`endDate` 计算快捷截止默认值）。
- **state**：`articles: ArticleVO[]`、`users`（负责人下拉）、`loading`、`modalOpen`、`editing`、`reviewTarget`、`publishTarget`、`statsTarget`、各 form。
- **数据**：`fetchList` 调 `listArticles({ activityId, page: 1, size: 1000 })`。
- **头部**：
  - `canManage`（`MEDIA_LEADER` 或 `roleLevel>=4`）时显示：
    - 「新建推文」（打开空表单，默认 deadline 今天）
    - 「快捷创建预热」（`createArticle` 直接建：title 预填「{活动名}预热推文」、articleType=PREHEAT、deadline=`dayjs(activity.startDate).subtract(3,'day')`、authorId 空、默认 DRAFT）
    - 「快捷创建报道」（articleType=REPORT、deadline=`dayjs(activity.endDate).add(2,'day')`）
  - 快捷创建后 `message.success` + 刷新。
- **卡片列表**（antd `Row/Col` 或 `List`）：每卡片显示
  - 标题 + 类型 Tag（`ARTICLE_TYPE_MAP`）+ 状态 Tag（`StatusTag status`）
  - 负责人（`userNameOf(authorId)`）+ 截止（逾期红）
  - 长图缩略（`imageUrls.map(u => <img style={{maxWidth:60,maxHeight:80,objectFit:'cover'}} />)`，点击弹大图预览 `GlassModal` 内 `<img width="100%">`）
  - 公众号链接（`wxUrl`，可点）+ 阅读/在看
- **操作**（按状态 + 权限，`canManage || a.authorId === user.id`）：
  - DRAFT/REJECTED：编辑、提交、删除
  - PENDING：审核（仅 `canReview`，即 `canManage`）
  - APPROVED：标记发布
  - PUBLISHED：更新数据
- **表单弹窗**（新建/编辑共用，`activityId` 固定为 props 值，不显示活动下拉）：
  - title、articleType、authorId（负责人 `Select`，编辑时非 leader 禁用）、deadline（`DatePicker showTime`）、summary、content（底稿 `TextArea`）、imageUrls（`Upload` 多图，`uploadFile` 上传后填 `/api/files/{id}/download`）
- **审核弹窗**：展示 `reviewTarget.imageUrls` 大图 + 标题/摘要 + `TextArea` 意见 + 通过/驳回（调 `reviewArticle`）
- **发布弹窗**：输入公众号链接（`Input`）→ `publishArticle`
- **数据弹窗**：阅读量/在看数 `InputNumber` → `updateArticleStats`
- **空态**：`Empty` 文案「该活动暂无推文任务，可点击「快捷创建预热」生成预热推文」。

- [ ] **Step 2: ActivityDetail 接线**

`ActivityDetail.tsx`：
- 顶部 import：`import ActivityArticlesTab from './ActivityArticlesTab'`（同目录 `./`）。
- `tabItems` 数组 `signin` 之后追加：
```tsx
{
  key: 'articles',
  label: '推文',
  children: <ActivityArticlesTab activityId={activityId} activity={activity} />,
},
```
（`activity` 变量在组件内已定义，为 `detail?.activity`。）

- [ ] **Step 3: 类型检查 + 构建**

Run: `npx tsc -b --noEmit` 然后 `npm run build`
Expected: 无类型错误，构建成功。

- [ ] **Step 4: Commit**

```bash
git add pams-web/src/pages/activity/ActivityArticlesTab.tsx pams-web/src/pages/activity/ActivityDetail.tsx
git commit -m "feat(web): 活动详情新增「推文」Tab（创建/长图/审核/发布/数据）"
```

---

## 集成验证（Workflow 收尾阶段执行）

- [ ] **Step 1: 合并两个 agent 分支到 main**

```bash
git merge --no-ff <backend-branch> <frontend-branch>
```
两分支文件集不相交，预期无冲突。

- [ ] **Step 2: 后端全量测试**

Run: `mvn -pl pams-backend test -q`
Expected: 全绿。

- [ ] **Step 3: 前端构建**

Run: `cd pams-web && npm run build`
Expected: 构建成功。

- [ ] **Step 4: 验收清单核对**

对照设计文档验收标准逐条核对（活动详情推文 Tab、长图审核、APPROVED 待发布、回填链接/数据、节点通知、截止提醒）。

## 非目标（本计划不实现）

- 公众号 API 对接（自动发布/拉数据）。
- 秀米 HTML 导入渲染（仅长图截图）。
- 阅读数据可视化报表。
