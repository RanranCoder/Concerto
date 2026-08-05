package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.PlanRequest;
import com.pams.module.activity.entity.Activity;
import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.entity.ActivityStatus;
import com.pams.module.activity.repository.ActivityPlanRepository;
import com.pams.module.activity.repository.ActivityRepository;
import com.pams.module.notification.event.PlanEditedEvent;
import com.pams.module.notification.event.PlanReviewedEvent;
import com.pams.module.notification.event.PlanSubmittedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;

@Service
public class PlanService {
    private final ActivityPlanRepository repository;
    private final ActivityRepository activityRepository;
    private final ApplicationEventPublisher eventPublisher;

    /** 生产构造器：注入活动仓库，用于 approve 时联动活动状态（PLANNING → PLAN_REVIEW）。 */
    @Autowired
    public PlanService(ActivityPlanRepository repository, ActivityRepository activityRepository,
                       ApplicationEventPublisher eventPublisher) {
        this.repository = repository;
        this.activityRepository = activityRepository;
        this.eventPublisher = eventPublisher;
    }

    /** 测试友好构造器：activityRepository 和 eventPublisher 为 null。 */
    public PlanService(ActivityPlanRepository repository) {
        this(repository, null, null);
    }

    public ActivityPlan latest(Long activityId) {
        // B5 fix: 用 Repository 查询替代全表扫描
        return repository.findTopByActivityIdOrderByVersionDesc(activityId).orElse(null);
    }

    public List<ActivityPlan> listByActivity(Long activityId) {
        // B5 fix: 用 Repository 查询替代全表扫描
        return repository.findByActivityIdOrderByVersionDesc(activityId);
    }

    @Transactional
    public ActivityPlan create(PlanRequest req) {
        // L3 fix: 校验活动是否存在
        if (activityRepository != null) {
            activityRepository.findById(req.getActivityId())
                    .orElseThrow(() -> new BizException(2001, "关联的活动不存在"));
        }
        ActivityPlan p = new ActivityPlan();
        p.setActivityId(req.getActivityId());
        // E5 fix: version 自增，不信任前端
        int ver = repository.findTopByActivityIdOrderByVersionDesc(req.getActivityId())
                .map(ActivityPlan::getVersion).orElse(0) + 1;
        p.setVersion(ver);
        apply(p, req);
        syncActivityIfNeeded(req.getActivityId(), req);
        p.setStatus(ActivityPlan.PlanStatus.DRAFT);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return repository.save(p);
    }

    @Transactional
    public void update(Long id, PlanRequest req) {
        update(id, req, null);
    }

    @Transactional
    public void update(Long id, PlanRequest req, Long editorId) {
        ActivityPlan p = getEntity(id);
        // L4 fix: PENDING 状态也不可修改（TOCTOU），加上 APPROVED
        if (p.getStatus() == ActivityPlan.PlanStatus.APPROVED
                || p.getStatus() == ActivityPlan.PlanStatus.PENDING) {
            throw new BizException(2003, "当前状态不可修改，请新建版本");
        }
        apply(p, req);
        syncActivityIfNeeded(p.getActivityId(), req);
        repository.save(p);
        // 发布策划书编辑事件（链路1：通知各部门；链路2：主任修改时通知组织部）
        if (eventPublisher != null && editorId != null) {
            String planTitle = activityRepository != null
                    ? activityRepository.findById(p.getActivityId())
                        .map(Activity::getName).orElse("未知活动")
                    : "未知活动";
            eventPublisher.publishEvent(new PlanEditedEvent(
                    p.getId(), p.getActivityId(), planTitle, editorId));
        }
    }

    @Transactional
    public void submit(Long id, Long submitterId) {
        ActivityPlan p = getEntity(id);
        if (p.getStatus() != ActivityPlan.PlanStatus.DRAFT
                && p.getStatus() != ActivityPlan.PlanStatus.REJECTED) {
            throw new BizException(2005, "当前状态不可提交审核");
        }
        p.setStatus(ActivityPlan.PlanStatus.PENDING);
        p.setSubmitterId(submitterId);
        p.setUpdatedAt(LocalDateTime.now());
        repository.save(p);
        // 发布策划书提交事件
        if (eventPublisher != null) {
            String planTitle = activityRepository != null
                    ? activityRepository.findById(p.getActivityId())
                        .map(Activity::getName).orElse("未知活动")
                    : "未知活动";
            eventPublisher.publishEvent(new PlanSubmittedEvent(
                    p.getId(), p.getActivityId(), planTitle, submitterId));
        }
    }

    /**
     * 审核策划书。仅 PENDING 可审核；approve 时联动把关联活动状态推到 PLAN_REVIEW
     * （仅当活动当前为 PLANNING）。
     */
    @Transactional
    public void review(Long id, boolean approved, String comment, Long reviewerId) {
        ActivityPlan p = getEntity(id);
        if (p.getStatus() != ActivityPlan.PlanStatus.PENDING) {
            throw new BizException(2006, "仅待审核状态的策划书可审核");
        }
        p.setStatus(approved ? ActivityPlan.PlanStatus.APPROVED : ActivityPlan.PlanStatus.REJECTED);
        p.setReviewerId(reviewerId);
        p.setReviewComment(comment);
        p.setUpdatedAt(LocalDateTime.now());
        repository.save(p);
        if (approved && activityRepository != null) {
            activityRepository.findById(p.getActivityId()).ifPresent(a -> {
                if (a.getStatus() == ActivityStatus.PLANNING) {
                    a.setStatus(ActivityStatus.PLAN_REVIEW);
                    activityRepository.save(a);
                }
            });
        }
        // 发布策划书审核事件
        if (eventPublisher != null) {
            String planTitle = activityRepository != null
                    ? activityRepository.findById(p.getActivityId())
                        .map(Activity::getName).orElse("未知活动")
                    : "未知活动";
            Long submitterId = p.getSubmitterId();
            eventPublisher.publishEvent(new PlanReviewedEvent(
                    p.getId(), p.getActivityId(), planTitle,
                    reviewerId, approved, comment, submitterId));
        }
    }

    private void apply(ActivityPlan p, PlanRequest req) {
        p.setBackground(req.getBackground());
        p.setPurpose(req.getPurpose());
        p.setContent(req.getContent());
        p.setFlow(req.getFlow());
        p.setNotice(req.getNotice());
        p.setEmergency(req.getEmergency());
        p.setBudget(req.getBudget());
        p.setNameOverride(req.getNameOverride());
        p.setThemeOverride(req.getThemeOverride());
        p.setTimeOverride(req.getTimeOverride());
        p.setLocationOverride(req.getLocationOverride());
        p.setOrganizerOverride(req.getOrganizerOverride());
        p.setTargetOverride(req.getTargetOverride());
        p.setSectionOrder(req.getSectionOrder());
        p.setUpdatedAt(LocalDateTime.now());
    }

    /**
     * 用户确认「同步更新活动基本信息」时，把 override 值回写到 activity 表。
     * 仅当对应 override 非空才写；time_override 为 "YYYY-MM-DD|时间段" 形式，日期部分映射 startDate/endDate。
     */
    private void syncActivityIfNeeded(Long activityId, PlanRequest req) {
        if (!req.isSyncActivity() || activityRepository == null) return;
        Activity a = activityRepository.findById(activityId).orElse(null);
        if (a == null) return;
        boolean dirty = false;
        if (req.getNameOverride() != null && !req.getNameOverride().trim().isEmpty()) {
            a.setName(req.getNameOverride().trim());
            dirty = true;
        }
        if (req.getThemeOverride() != null && !req.getThemeOverride().trim().isEmpty()) {
            a.setTheme(req.getThemeOverride().trim());
            dirty = true;
        }
        if (req.getLocationOverride() != null && !req.getLocationOverride().trim().isEmpty()) {
            a.setLocation(req.getLocationOverride().trim());
            dirty = true;
        }
        if (req.getOrganizerOverride() != null && !req.getOrganizerOverride().trim().isEmpty()) {
            a.setOrganizer(req.getOrganizerOverride().trim());
            dirty = true;
        }
        if (req.getTargetOverride() != null && !req.getTargetOverride().trim().isEmpty()) {
            a.setTargetAudience(req.getTargetOverride().trim());
            dirty = true;
        }
        if (req.getTimeOverride() != null && !req.getTimeOverride().trim().isEmpty()) {
            String[] parts = req.getTimeOverride().split("\\|", 2);
            LocalDate date = parseDate(parts[0].trim());
            if (date != null) {
                a.setStartDate(date);
                a.setEndDate(null);
                if (parts.length > 1) {
                    String seg = parts[1] == null ? "" : parts[1];
                    String[] range = seg.split("[~至]");
                    LocalDate end = range.length > 1 ? parseDate(range[1].trim()) : null;
                    a.setEndDate(end);
                }
                dirty = true;
            }
        }
        if (dirty) {
            a.setUpdatedAt(LocalDateTime.now());
            activityRepository.save(a);
        }
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        try {
            return LocalDate.parse(s.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public ActivityPlan getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2004, "策划书不存在"));
    }
}
