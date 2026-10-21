package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.dto.PlanRequest;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.notification.event.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.time.format.DateTimeParseException;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class PlanService {
    private final ActivityPlanRepository repository;
    private final ActivityRepository activityRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final PlanAccessService access;
    private final PlanAttachmentService attachments;

    public PlanService(ActivityPlanRepository repository, ActivityRepository activities,
                       ApplicationEventPublisher events, PlanAccessService access,PlanAttachmentService attachments) {
        this.repository=repository; this.activityRepository=activities; this.eventPublisher=events; this.access=access;
        this.attachments=attachments;
    }

    private ToolContext context() { return ToolContext.current(null,null); }
    private ActivityPlan raw(Long id) {
        return repository.findById(id).orElseThrow(() -> new BizException(2004,"策划书不存在或不可见"));
    }
    public ActivityPlan getEntity(Long id) {
        ActivityPlan p=raw(id);
        if (!access.canRead(p,context())) throw new BizException(403,"当前版本仅作者和所选审核人可见");
        return p;
    }
    public List<ActivityPlan> listByActivity(Long activityId) {
        return repository.findByActivityIdOrderByVersionDesc(activityId).stream()
                .filter(p -> access.canRead(p,context())).toList();
    }
    public ActivityPlan latest(Long activityId) {
        return listByActivity(activityId).stream().findFirst().orElse(null);
    }
    public record Version(Long id,Integer version,ActivityPlan.PlanStatus status,LocalDateTime updatedAt,
                          Long ownerId,String ownerName,String note) {}
    public Map<String,Object> workspace(Long activityId) {
        var visible=listByActivity(activityId);
        var current=repository.findTopByActivityIdOrderByVersionDesc(activityId).orElse(null);
        var latest=visible.stream().findFirst().orElse(null);
        var result=new LinkedHashMap<String,Object>();
        result.put("latest",latest);
        result.put("status",latest==null?null:latest.getStatus());
        result.put("currentHidden",current!=null && !access.canRead(current,context()));
        result.put("currentId",current!=null && access.canRead(current,context())?current.getId():null);
        var names=access.authorNames(visible,context());
        result.put("versions",visible.stream().map(p -> {
            Long owner=access.owner(p);
            String note=Objects.equals(p.getId(),current==null?null:current.getId())?"当前工作版本":switch(p.getStatus()) {
                case APPROVED -> "审核通过的定稿。";
                case REJECTED -> Objects.toString(p.getReviewComment(),"退回后保留的提交版本。");
                case WITHDRAWN -> "作者撤回审核，原稿与审核记录已保留。";
                case PENDING -> "本次提交的审核版本。";
                case DRAFT -> "建立策划框架与活动安排。";
            };
            return new Version(p.getId(),p.getVersion(),p.getStatus(),p.getUpdatedAt(),owner,
                    owner==null?"未记录作者":names.getOrDefault(owner,"已离开组织的成员"),note);
        }).toList());
        result.put("canEdit",current==null ? context().hasPermission("activity:edit")
                : Objects.equals(access.owner(current),context().getUserId()) && context().hasPermission("activity:edit"));
        return result;
    }
    private Activity lockActivity(Long activityId,ToolContext ctx) {
        Activity a=activityRepository.lockById(activityId).orElseThrow(() -> new BizException(2001,"关联的活动不存在"));
        if (ctx.getLoginUser()==null || !Objects.equals(a.getOrgId(),ctx.getLoginUser().getOrgId()))
            throw new BizException(403,"活动不可见");
        return a;
    }
    private ActivityPlan writable(Long id) {
        ActivityPlan p=raw(id);
        lockActivity(p.getActivityId(),context());
        // All mutations lock the activity before reloading the plan, serializing versions and decisions.
        entityManager.refresh(p);
        access.requireOwner(p,context());
        return p;
    }
    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    @Transactional
    public ActivityPlan create(PlanRequest req) { return create(req,context()); }
    @Transactional
    public ActivityPlan create(PlanRequest req,ToolContext ctx) {
        if (ctx==null || !ctx.hasPermission("activity:edit") || ctx.getUserId()==null)
            throw new BizException(403,"无权创建策划书");
        Activity a=lockActivity(req.getActivityId(),ctx);
        var previous=repository.findTopByActivityIdOrderByVersionDesc(req.getActivityId()).orElse(null);
        if (previous!=null) {
            access.requireOwner(previous,ctx);
            if (previous.getStatus()==ActivityPlan.PlanStatus.PENDING)
                throw new BizException(2003,"审核中不可新建版本，请先撤回");
            if (previous.getStatus()==ActivityPlan.PlanStatus.DRAFT)
                throw new BizException(2003,"已有草稿，请直接编辑保存");
        }
        attachments.validate(req,ctx);
        ActivityPlan p=new ActivityPlan();
        p.setActivityId(req.getActivityId()); p.setOrgId(a.getOrgId());
        p.setOwnerId(previous==null?ctx.getUserId():access.owner(previous));
        p.setVersion(previous==null?1:previous.getVersion()+1);
        apply(p,req); syncActivityIfNeeded(req.getActivityId(),req); freezeMetadata(p,a);
        p.setStatus(ActivityPlan.PlanStatus.DRAFT); p.setCreatedAt(LocalDateTime.now());
        return repository.save(p);
    }
    @Transactional
    public void update(Long id,PlanRequest req,Long editorId) {
        ActivityPlan p=writable(id);
        if (req.getExpectedRevision()!=null && !Objects.equals(req.getExpectedRevision(),p.getRevision()))
            throw new BizException(409,"此草稿已被更新，请先保留您的输入并重新载入版本");
        if (req.getExpectedUpdatedAt()!=null && !Objects.equals(req.getExpectedUpdatedAt(),p.getUpdatedAt()))
            throw new BizException(409,"此草稿已被更新，请先保留您的输入并重新载入版本");
        if (!Objects.equals(p.getActivityId(),req.getActivityId())) throw new BizException(2003,"不能修改关联活动");
        if (p.getStatus()!=ActivityPlan.PlanStatus.DRAFT)
            throw new BizException(2003,"当前状态不可修改，请新建版本");
        attachments.validate(req,context());
        apply(p,req); syncActivityIfNeeded(p.getActivityId(),req);
        freezeMetadata(p,activityRepository.findById(p.getActivityId()).orElseThrow());
        repository.save(p);
        eventPublisher.publishEvent(new PlanEditedEvent(p.getId(),p.getActivityId(),p.getNameOverride(),editorId));
    }
    @Transactional
    public void submit(Long id,Long submitterId,List<Long> reviewerIds) {
        ActivityPlan p=writable(id);
        if (p.getStatus()!=ActivityPlan.PlanStatus.DRAFT) throw new BizException(2005,"当前状态不可提交审核");
        if (reviewerIds==null || reviewerIds.isEmpty() || reviewerIds.size()>50
                || new HashSet<>(reviewerIds).size()!=reviewerIds.size())
            throw new BizException(2005,"请选择 1 至 50 位不重复的审核人");
        var candidates=access.candidates(context());
        var selected=new ArrayList<PlanReviewer>();
        for (Long uid:reviewerIds) {
            var candidate=candidates.stream().filter(c -> Objects.equals(c.id(),uid)).findFirst()
                    .orElseThrow(() -> new BizException(403,"所选审核人已失效，请重新选择"));
            var r=new PlanReviewer(); r.setUserId(uid); r.setName(candidate.name()); selected.add(r);
        }
        p.getReviewers().clear(); p.getReviewers().addAll(selected);
        p.setStatus(ActivityPlan.PlanStatus.PENDING); p.setSubmitterId(context().getUserId());
        p.setUpdatedAt(LocalDateTime.now()); repository.save(p);
        eventPublisher.publishEvent(new PlanSubmittedEvent(p.getId(),p.getActivityId(),p.getNameOverride(),
                p.getSubmitterId(),reviewerIds));
    }
    @Transactional
    public void review(Long id,boolean approved,String comment,Long reviewerId) {
        var ctx=context(); ActivityPlan p=raw(id); lockActivity(p.getActivityId(),ctx); entityManager.refresh(p);
        if (!access.canRead(p,ctx) || !ctx.hasPermission("activity:review")
                || !Objects.equals(ctx.getUserId(),reviewerId)) throw new BizException(403,"未被选为此版本审核人");
        if (!access.isCurrent(p) || p.getStatus()!=ActivityPlan.PlanStatus.PENDING)
            throw new BizException(2006,"仅待审核状态的当前策划书可审核");
        var r=p.getReviewers().stream().filter(v -> Objects.equals(v.getUserId(),reviewerId)).findFirst()
                .orElseThrow(() -> new BizException(403,"未被选为此版本审核人"));
        if (r.getApproved()!=null) throw new BizException(2006,"您已审核此版本");
        if (comment!=null && comment.length()>1000) throw new BizException(2006,"审核意见最多 1000 字");
        if (!approved && (comment==null || comment.isBlank())) throw new BizException(2006,"退回时请填写修改意见");
        r.setApproved(approved); r.setComment(comment); r.setReviewedAt(LocalDateTime.now());
        p.setReviewerId(reviewerId); p.setReviewComment(comment); p.setUpdatedAt(LocalDateTime.now());
        boolean complete=approved && p.getReviewers().stream().allMatch(v -> Boolean.TRUE.equals(v.getApproved()));
        if (!approved) p.setStatus(ActivityPlan.PlanStatus.REJECTED);
        else if (complete) p.setStatus(ActivityPlan.PlanStatus.APPROVED);
        repository.save(p);
        if (complete || !approved) eventPublisher.publishEvent(new PlanReviewedEvent(p.getId(),p.getActivityId(),
                p.getNameOverride(),reviewerId,approved,comment,p.getSubmitterId()));
    }
    @Transactional
    public ActivityPlan revise(Long id) {
        var p=writable(id);
        if (p.getStatus()==ActivityPlan.PlanStatus.DRAFT) throw new BizException(2003,"已有草稿，请直接编辑");
        // Withdrawal preserves the original submission and decisions as a read-only snapshot.
        if (p.getStatus()==ActivityPlan.PlanStatus.PENDING) {
            p.setStatus(ActivityPlan.PlanStatus.WITHDRAWN); p.setReviewComment("作者已撤回");
            p.setUpdatedAt(LocalDateTime.now()); repository.saveAndFlush(p);
        }
        PlanRequest req=new PlanRequest();
        org.springframework.beans.BeanUtils.copyProperties(p,req);
        req.setActivityId(p.getActivityId()); req.setSyncActivity(false);
        return create(req,context());
    }
    private void freezeMetadata(ActivityPlan p,Activity a) {
        if(p.getNameOverride()==null) p.setNameOverride(Objects.toString(a.getName(),""));
        if(p.getThemeOverride()==null) p.setThemeOverride(Objects.toString(a.getTheme(),""));
        if(p.getLocationOverride()==null) p.setLocationOverride(Objects.toString(a.getLocation(),""));
        if(p.getOrganizerOverride()==null) p.setOrganizerOverride(Objects.toString(a.getOrganizer(),""));
        if(p.getTargetOverride()==null) p.setTargetOverride(Objects.toString(a.getTargetAudience(),""));
        if(p.getTimeOverride()==null) p.setTimeOverride(a.getStartDate()==null?"":a.getStartDate().toString()
                +(a.getEndDate()==null || a.getEndDate().equals(a.getStartDate())?"":" ~ "+a.getEndDate()));
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

}
