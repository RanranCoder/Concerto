package com.pams.module.content.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.module.content.dto.ArticleRequest;
import com.pams.module.content.entity.Article;
import com.pams.module.content.repository.ArticleRepository;
import com.pams.module.notification.event.ContentUploadedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class ArticleService {
    private final ArticleRepository repository;
    private final ApplicationEventPublisher eventPublisher;

    public ArticleService(ArticleRepository repository) {
        this(repository, null);
    }

    @Autowired
    public ArticleService(ArticleRepository repository, ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.eventPublisher = eventPublisher;
    }

    public PageResult<Map<String, Object>> page(String status, String type, String keyword, int page, int size) {
        Page<Article> p = repository.findAll((root, q, cb) -> {
            var preds = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
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
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(this::toVo).toList());
        r.setTotal(p.getTotalElements()); r.setCurrent(page); r.setSize(size);
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
        vo.put("articleType", a.getArticleType() == null ? "REPORT" : a.getArticleType().name());
        vo.put("status", a.getStatus() == null ? "DRAFT" : a.getStatus().name());
        vo.put("authorId", a.getAuthorId());
        vo.put("reviewerId", a.getReviewerId());
        vo.put("reviewComment", a.getReviewComment() == null ? "" : a.getReviewComment());
        vo.put("publishTime", a.getPublishTime());
        vo.put("createdAt", a.getCreatedAt());
        vo.put("updatedAt", a.getUpdatedAt());
        return vo;
    }

    public Article getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2001, "推文不存在"));
    }

    @Transactional
    public Article create(Long authorId, ArticleRequest req) {
        Article a = new Article();
        a.setStatus(Article.ArticleStatus.DRAFT);
        a.setAuthorId(authorId);
        apply(a, req);
        a.setDeleted(0);
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        Article saved = repository.save(a);
        // 链路3：推文上传 → 通知各部长（上传者本人除外）
        if (eventPublisher != null && authorId != null) {
            eventPublisher.publishEvent(new ContentUploadedEvent(
                    saved.getId(), saved.getActivityId(), saved.getTitle(), "ARTICLE", authorId));
        }
        return saved;
    }

    @Transactional
    public void update(Long id, ArticleRequest req) {
        Article a = getEntity(id);
        if (a.getStatus() == Article.ArticleStatus.PUBLISHED) {
            throw new BizException(2003, "已发布的推文不可修改");
        }
        apply(a, req);
        repository.save(a);
    }

    @Transactional
    public void submit(Long id) {
        Article a = getEntity(id);
        if (a.getStatus() != Article.ArticleStatus.DRAFT
                && a.getStatus() != Article.ArticleStatus.REJECTED) {
            throw new BizException(2005, "当前状态不可提交审核");
        }
        a.setStatus(Article.ArticleStatus.PENDING);
        a.setUpdatedAt(LocalDateTime.now());
        repository.save(a);
    }

    /**
     * 审核推文。仅 PENDING 可审核；approve 时置为 PUBLISHED 并写入 publishTime，
     * reject 时置为 REJECTED，作者可修改后重新提交。
     */
    @Transactional
    public void review(Long id, boolean approved, String comment, Long reviewerId) {
        Article a = getEntity(id);
        if (a.getStatus() != Article.ArticleStatus.PENDING) {
            throw new BizException(2006, "仅待审核状态的推文可审核");
        }
        a.setStatus(approved ? Article.ArticleStatus.PUBLISHED : Article.ArticleStatus.REJECTED);
        if (approved) {
            a.setPublishTime(LocalDateTime.now());
        }
        a.setReviewerId(reviewerId);
        a.setReviewComment(comment);
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
        a.setActivityId(req.getActivityId());
        if (req.getArticleType() != null && !req.getArticleType().isBlank()) {
            a.setArticleType(Article.ArticleType.valueOf(req.getArticleType()));
        }
        a.setUpdatedAt(LocalDateTime.now());
    }
}
