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
