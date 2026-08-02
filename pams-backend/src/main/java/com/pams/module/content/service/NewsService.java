package com.pams.module.content.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.module.content.dto.NewsRequest;
import com.pams.module.content.entity.News;
import com.pams.module.content.repository.NewsRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class NewsService {
    private final NewsRepository repository;
    public NewsService(NewsRepository repository) { this.repository = repository; }

    public PageResult<Map<String, Object>> page(String keyword, int page, int size) {
        Page<News> p = repository.findAll((root, q, cb) -> {
            if (keyword == null || keyword.isBlank()) {
                return cb.conjunction();
            }
            String like = "%" + keyword.trim() + "%";
            return cb.or(cb.like(root.get("title"), like), cb.like(root.get("subtitle"), like));
        }, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(this::toVo).toList());
        r.setTotal(p.getTotalElements()); r.setCurrent(page); r.setSize(size);
        return r;
    }

    private Map<String, Object> toVo(News n) {
        Map<String, Object> vo = new LinkedHashMap<>();
        vo.put("id", n.getId());
        vo.put("title", n.getTitle());
        vo.put("subtitle", n.getSubtitle() == null ? "" : n.getSubtitle());
        vo.put("content", n.getContent() == null ? "" : n.getContent());
        vo.put("activityId", n.getActivityId());
        vo.put("authorId", n.getAuthorId());
        vo.put("publishDate", n.getPublishDate());
        vo.put("status", n.getStatus() == null ? "DRAFT" : n.getStatus().name());
        vo.put("createdAt", n.getCreatedAt());
        vo.put("updatedAt", n.getUpdatedAt());
        return vo;
    }

    public News getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2001, "新闻稿不存在"));
    }

    @Transactional
    public News create(Long authorId, NewsRequest req) {
        News n = new News();
        n.setStatus(News.NewsStatus.DRAFT);
        n.setAuthorId(authorId);
        apply(n, req);
        n.setDeleted(0);
        n.setCreatedAt(LocalDateTime.now());
        n.setUpdatedAt(LocalDateTime.now());
        return repository.save(n);
    }

    @Transactional
    public void update(Long id, NewsRequest req) {
        News n = getEntity(id);
        apply(n, req);
        repository.save(n);
    }

    @Transactional
    public void delete(Long id) {
        News n = getEntity(id);
        n.setDeleted(1);
        n.setUpdatedAt(LocalDateTime.now());
        repository.save(n);
    }

    private void apply(News n, NewsRequest req) {
        n.setTitle(req.getTitle());
        n.setSubtitle(req.getSubtitle());
        n.setContent(req.getContent());
        n.setActivityId(req.getActivityId());
        n.setUpdatedAt(LocalDateTime.now());
    }
}
