package com.pams.module.activity.service;

import com.pams.common.BizException;
import com.pams.module.activity.entity.*;
import com.pams.module.activity.repository.*;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.permission.service.TenantAuthorizationService;
import com.pams.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;

@Service
@Transactional(readOnly = true)
public class PlanAccessService {
    private final ActivityPlanRepository plans;
    private final ActivityRepository activities;
    private final UserRepository users;
    private final TenantAuthorizationService authorization;

    public PlanAccessService(ActivityPlanRepository plans, ActivityRepository activities,
                             UserRepository users, TenantAuthorizationService authorization) {
        this.plans=plans; this.activities=activities; this.users=users; this.authorization=authorization;
    }

    public Long owner(ActivityPlan plan) {
        if (plan.getOwnerId()!=null) return plan.getOwnerId();
        if (plan.getSubmitterId()!=null) return plan.getSubmitterId();
        return activities.findById(plan.getActivityId()).map(Activity::getCreatedBy).orElse(null);
    }

    public boolean isCurrent(ActivityPlan plan) {
        return plans.findTopByActivityIdOrderByVersionDesc(plan.getActivityId())
                .map(p -> Objects.equals(p.getId(),plan.getId())).orElse(false);
    }

    public boolean canRead(ActivityPlan plan, ToolContext ctx) {
        if (ctx==null || ctx.getLoginUser()==null || !ctx.hasPermission("activity:view")
                || !Objects.equals(plan.getOrgId(),ctx.getLoginUser().getOrgId())) return false;
        if (!isCurrent(plan)) return true;
        return Objects.equals(owner(plan),ctx.getUserId())
                || plan.getReviewers().stream().anyMatch(r -> Objects.equals(r.getUserId(),ctx.getUserId()));
    }

    public boolean canRead(Long id, ToolContext ctx) {
        return plans.findById(id).map(p -> canRead(p,ctx)).orElse(false);
    }

    /** Null means an unbound upload; only the uploader may preview an unbound PLAN file. */
    public Boolean canReadAttachment(Long fileId, ToolContext ctx) {
        return canReadAttachment(fileId,null,ctx);
    }
    public Boolean canReadAttachment(Long fileId,String storedPath,ToolContext ctx) {
        var references=new ArrayList<>(plans.findByDocumentFileId(fileId));
        references.addAll(plans.referencingFile("%/api/files/"+fileId+"/%"));
        if(storedPath!=null) references.addAll(plans.referencingFile("%/uploads/"+storedPath+"%"));
        return references.isEmpty()?null:references.stream().anyMatch(p -> canRead(p,ctx));
    }

    public void requireOwner(ActivityPlan plan, ToolContext ctx) {
        if (!canRead(plan,ctx) || !Objects.equals(owner(plan),ctx.getUserId())
                || !ctx.hasPermission("activity:edit")) throw new BizException(403,"仅策划书作者可修改或提交当前版本");
        if (!isCurrent(plan)) throw new BizException(2003,"历史版本只读，请操作当前版本");
    }

    public record Candidate(Long id, String name, String deptName, String roleName) {}

    public Map<Long,String> authorNames(List<ActivityPlan> visible,ToolContext ctx) {
        var ids=visible.stream().map(this::owner).filter(Objects::nonNull).distinct().toList();
        var names=new HashMap<Long,String>();
        if(ctx==null || ctx.getLoginUser()==null) return names;
        for(var user:users.findAllById(ids)) {
            if(Objects.equals(user.getOrgId(),ctx.getLoginUser().getOrgId()))
                names.put(user.getId(),user.getRealName()==null?user.getUsername():user.getRealName());
        }
        return names;
    }

    public List<Candidate> candidates(ToolContext ctx) {
        if (ctx==null || ctx.getLoginUser()==null || !ctx.hasPermission("activity:edit"))
            throw new BizException(403,"无权选择审核人");
        return users.findByOrgIdOrderByIdAsc(ctx.getLoginUser().getOrgId()).stream()
                .filter(u -> !Objects.equals(u.getId(),ctx.getUserId()))
                .filter(u -> authorization.valid(u.getId()) && authorization.business(u.getId())
                        .containsAll(Set.of("activity:view","activity:review")))
                .map(u -> new Candidate(u.getId(),u.getRealName()==null?u.getUsername():u.getRealName(),
                        u.getDept()==null?"":u.getDept().getName(),u.getRole()==null?"":u.getRole().getName())).toList();
    }
}
