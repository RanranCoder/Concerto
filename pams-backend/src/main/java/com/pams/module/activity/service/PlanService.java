package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.PlanRequest;
import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.repository.ActivityPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class PlanService {
    private final ActivityPlanRepository repository;
    public PlanService(ActivityPlanRepository repository) { this.repository = repository; }

    public ActivityPlan latest(Long activityId) {
        return repository.findAll().stream()
                .filter(p -> p.getActivityId().equals(activityId))
                .max(java.util.Comparator.comparingInt(ActivityPlan::getVersion))
                .orElse(null);
    }

    public List<ActivityPlan> listByActivity(Long activityId) {
        return repository.findAll().stream()
                .filter(p -> p.getActivityId().equals(activityId))
                .sorted(java.util.Comparator.comparingInt(ActivityPlan::getVersion).reversed())
                .toList();
    }

    @Transactional
    public ActivityPlan create(PlanRequest req) {
        ActivityPlan p = new ActivityPlan();
        p.setActivityId(req.getActivityId());
        p.setVersion(req.getVersion() == null ? 1 : req.getVersion());
        apply(p, req);
        p.setStatus(ActivityPlan.PlanStatus.DRAFT);
        p.setCreatedAt(LocalDateTime.now());
        p.setUpdatedAt(LocalDateTime.now());
        return repository.save(p);
    }

    @Transactional
    public void update(Long id, PlanRequest req) {
        ActivityPlan p = getEntity(id);
        if (p.getStatus() == ActivityPlan.PlanStatus.APPROVED) {
            throw new BizException(2003, "已审核通过的策划书不可修改，请新建版本");
        }
        apply(p, req);
        repository.save(p);
    }

    @Transactional
    public void submit(Long id) {
        ActivityPlan p = getEntity(id);
        p.setStatus(ActivityPlan.PlanStatus.PENDING);
        p.setUpdatedAt(LocalDateTime.now());
        repository.save(p);
    }

    /**
     * 审核策划书。Task 11 将在此处（approve 时）联动把活动状态推到 PLAN_REVIEW。
     */
    @Transactional
    public void review(Long id, boolean approved, String comment, Long reviewerId) {
        ActivityPlan p = getEntity(id);
        p.setStatus(approved ? ActivityPlan.PlanStatus.APPROVED : ActivityPlan.PlanStatus.REJECTED);
        p.setReviewerId(reviewerId);
        p.setReviewComment(comment);
        p.setUpdatedAt(LocalDateTime.now());
        repository.save(p);
    }

    private void apply(ActivityPlan p, PlanRequest req) {
        p.setBackground(req.getBackground());
        p.setPurpose(req.getPurpose());
        p.setContent(req.getContent());
        p.setFlow(req.getFlow());
        p.setNotice(req.getNotice());
        p.setEmergency(req.getEmergency());
        p.setBudget(req.getBudget());
        p.setUpdatedAt(LocalDateTime.now());
    }

    public ActivityPlan getEntity(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2004, "策划书不存在"));
    }
}
