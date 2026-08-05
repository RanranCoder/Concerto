package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.common.PageResult;
import com.pams.module.activity.dto.ActivityRequest;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.security.LoginUser;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

@Service
public class ActivityService {
    private final ActivityRepository repository;
    public ActivityService(ActivityRepository repository) { this.repository = repository; }

    /** 从 SecurityContext 取当前登录用户 ID，未登录返回 null */
    private Long currentUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser u) {
            return u.getId();
        }
        return null;
    }

    public PageResult<Map<String, Object>> page(String keyword, String status, String type, int page, int size) {
        // B3 fix: 兜底 page <= 0
        page = Math.max(page, 1);
        size = Math.min(Math.max(size, 1), 100); // EDGE-1: size 上限 100

        Page<Activity> p = repository.findAll((root, q, cb) -> {
            var preds = new java.util.ArrayList<jakarta.persistence.criteria.Predicate>();
            if (keyword != null && !keyword.isBlank()) {
                String like = "%" + keyword.trim() + "%";
                preds.add(cb.or(cb.like(root.get("name"), like), cb.like(root.get("theme"), like)));
            }
            // B1 fix: String → Enum 转换，防止 Hibernate 报错
            if (status != null && !status.isBlank()) {
                try {
                    preds.add(cb.equal(root.get("status"), ActivityStatus.valueOf(status)));
                } catch (IllegalArgumentException ignored) {
                    // 非法状态值直接跳过该筛选条件
                }
            }
            if (type != null && !type.isBlank()) preds.add(cb.equal(root.get("type"), type));
            return cb.and(preds.toArray(new jakarta.persistence.criteria.Predicate[0]));
        }, PageRequest.of(page - 1, size, Sort.by(Sort.Direction.DESC, "id")));

        PageResult<Map<String, Object>> r = new PageResult<>();
        r.setRecords(p.getContent().stream().map(a -> {
            // 用 LinkedHashMap 而非 Map.of：Map.of 最多 10 对键值，且遇 null value 抛 NPE
            // （startDate/endDate/createdAt 等 DB 列可为空）
            Map<String, Object> vo = new java.util.LinkedHashMap<>();
            vo.put("id", a.getId()); vo.put("name", a.getName()); vo.put("theme", a.getTheme() == null ? "" : a.getTheme());
            vo.put("type", a.getType() == null ? "OTHER" : a.getType()); vo.put("status", a.getStatus().name());
            vo.put("startDate", a.getStartDate()); vo.put("endDate", a.getEndDate()); vo.put("location", a.getLocation() == null ? "" : a.getLocation());
            vo.put("organizer", a.getOrganizer() == null ? "" : a.getOrganizer()); vo.put("host", a.getHost() == null ? "" : a.getHost());
            vo.put("leader", a.getLeader() == null ? "" : a.getLeader()); vo.put("createdAt", a.getCreatedAt());
            return vo;
        }).toList());
        r.setTotal(p.getTotalElements()); r.setCurrent(page); r.setSize(size);
        return r;
    }

    public Activity getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2001, "活动不存在"));
    }

    public Map<String, Object> detail(Long id) {
        Activity a = getEntity(id);
        Map<String, Object> vo = new java.util.LinkedHashMap<>();
        vo.put("id", a.getId()); vo.put("name", a.getName()); vo.put("theme", a.getTheme() == null ? "" : a.getTheme());
        vo.put("type", a.getType() == null ? "OTHER" : a.getType()); vo.put("status", a.getStatus().name());
        vo.put("startDate", a.getStartDate()); vo.put("endDate", a.getEndDate()); vo.put("location", a.getLocation() == null ? "" : a.getLocation());
        vo.put("organizer", a.getOrganizer() == null ? "" : a.getOrganizer());
        vo.put("targetAudience", a.getTargetAudience() == null ? "" : a.getTargetAudience());
        vo.put("host", a.getHost() == null ? "" : a.getHost()); vo.put("leader", a.getLeader() == null ? "" : a.getLeader());
        vo.put("description", a.getDescription() == null ? "" : a.getDescription());
        return vo;
    }

    @Transactional
    public Long create(ActivityRequest req) {
        Activity a = new Activity();
        a.setStatus(ActivityStatus.ASSIGNED);
        apply(a, req);
        a.setDeleted(0);
        // B4 fix: 记录创建人
        a.setCreatedBy(currentUserId());
        a.setCreatedAt(LocalDateTime.now());
        a.setUpdatedAt(LocalDateTime.now());
        return repository.save(a).getId();
    }

    @Transactional
    public void update(Long id, ActivityRequest req) {
        Activity a = getEntity(id);
        // LOGIC-2 fix: FINISHED / ARCHIVED 状态禁止编辑
        if (a.getStatus() == ActivityStatus.FINISHED || a.getStatus() == ActivityStatus.ARCHIVED) {
            throw new BizException(2008, "已结束或已归档的活动不可编辑");
        }
        apply(a, req);
        repository.save(a);
    }

    @Transactional
    public void changeStatus(Long id, ActivityStatus target) {
        Activity a = getEntity(id);
        if (!a.getStatus().canGoTo(target)) {
            throw new BizException(2002, "活动状态不允许从 " + a.getStatus() + " 变更为 " + target);
        }
        a.setStatus(target);
        a.setUpdatedAt(LocalDateTime.now());
        repository.save(a);
    }

    @Transactional
    public void delete(Long id) {
        Activity a = getEntity(id);
        a.setDeleted(1);
        a.setUpdatedAt(LocalDateTime.now());
        repository.save(a);
    }

    private void apply(Activity a, ActivityRequest req) {
        // EDGE-3: 校验日期先后关系
        if (req.getStartDate() != null && req.getEndDate() != null && req.getEndDate().isBefore(req.getStartDate())) {
            throw new BizException(2007, "结束日期不能早于开始日期");
        }
        a.setName(req.getName()); a.setTheme(req.getTheme()); a.setType(req.getType());
        a.setStartDate(req.getStartDate()); a.setEndDate(req.getEndDate()); a.setLocation(req.getLocation());
        a.setOrganizer(req.getOrganizer()); a.setTargetAudience(req.getTargetAudience());
        a.setHost(req.getHost()); a.setLeader(req.getLeader()); a.setDescription(req.getDescription());
        a.setUpdatedAt(LocalDateTime.now());
    }
}
