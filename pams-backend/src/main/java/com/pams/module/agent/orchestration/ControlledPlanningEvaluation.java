package com.pams.module.agent.orchestration;
import com.pams.common.*;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.service.AgentEvaluationMeter;
import org.springframework.stereotype.Service;
import java.util.*;

/** Plan-only comparison. Cost and semantic benefit are never inferred from structural validation. */
@Service
public class ControlledPlanningEvaluation {
    private final AgentProperties props;private final ControlledPlanningService planner;private final CoordinatorAgent rules;private final AgentEvaluationMeter meter;
    public ControlledPlanningEvaluation(AgentProperties props,ControlledPlanningService planner,CoordinatorAgent rules,AgentEvaluationMeter meter){this.props=props;this.planner=planner;this.rules=rules;this.meter=meter;}
    public Map<String,Object> compare(List<String> goals,ToolContext ctx){
        if(ctx==null||ctx.getLoginUser()==null||!Objects.equals(OrgContext.get(),ctx.getLoginUser().getOrgId())||!ctx.hasPermission("org:audit:view")||!props.isBenchmarkEnabled()||!props.getBenchmarkOrgIds().contains(OrgContext.get()))throw new BizException(403,"仅显式启用的隔离组织可运行规划对照");
        if(goals==null||goals.isEmpty()||goals.size()>10||goals.stream().anyMatch(g->g==null||g.isBlank()||g.length()>4000))throw new BizException(400,"需要1–10条有效目标");
        var samples=new ArrayList<Map<String,Object>>();
        for(String goal:goals){
            String stream="planning-eval-"+UUID.randomUUID();meter.open(stream,2,2048);
            long start=System.nanoTime();try{
                var baseline=rules.plan(goal);var candidate=planner.previewForEvaluation(goal,ctx,stream);
                samples.add(Map.of("goal",goal,"ruleBaseline",baseline,"modelCandidate",candidate,"modelMetrics",meter.snapshot(stream),"ruleModelCalls",0,"elapsedMs",(System.nanoTime()-start)/1000000));
            }finally{meter.close(stream);}
        }
        return Map.of("samples",samples,"commonBudget",Map.of("maxCalls",2,"perCallOutputTokenCap",2048),"previewOnly",true,"businessConfirmed",false,"qualityVerdict","NOT_ASSESSED","releaseEligible",false);
    }
}
