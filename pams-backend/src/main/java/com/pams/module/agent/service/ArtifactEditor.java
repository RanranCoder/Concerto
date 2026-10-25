package com.pams.module.agent.service;

import com.pams.common.BizException;
import com.pams.module.agent.core.*;
import com.pams.module.agent.repository.*;
import com.pams.module.permission.service.TenantAuthorizationService;
import org.springframework.stereotype.Service;
import java.util.*;

/** Reads and reviews outside the mutation transaction; the gate checks the version again under locks. */
@Service
public class ArtifactEditor {
    private final ArtifactLedger ledger;
    private final DraftContent drafts;
    private final TaskGate gate;
    private final AgentTaskRepository tasks;
    private final AgentWorkItemRepository nodes;
    private final ToolRegistry tools;
    private final TenantAuthorizationService authorization;
    private final PlanReviewService reviews;
    public ArtifactEditor(ArtifactLedger ledger,DraftContent drafts,TaskGate gate,AgentTaskRepository tasks,AgentWorkItemRepository nodes,
                          ToolRegistry tools,TenantAuthorizationService authorization,PlanReviewService reviews) {
        this.ledger=ledger;this.drafts=drafts;this.gate=gate;this.tasks=tasks;this.nodes=nodes;this.tools=tools;this.authorization=authorization;this.reviews=reviews;
    }
    public Long edit(Long artifactId,int expected,Map<String,String> fields,ToolContext input) {
        var ctx=authorization.refresh(input);var artifact=ledger.require(artifactId,ctx);
        if(expected!=artifact.getCurrentVersion()) throw new BizException(409,"成果已有新版本，请保留修改并重新核对");
        var version=ledger.version(artifactId,expected);var task=tasks.findByIdAndUserId(version.getTaskId(),ctx.getUserId()).orElseThrow();
        if(!Set.of("PENDING_CONFIRM","REVIEW_REQUIRED").contains(task.getStatus())) throw new BizException(409,"已提交或失效的成果只能查看，不能再次保存为待确认草稿");
        if(!"NORMAL".equals(task.getDataSensitivity())) throw new BizException(403,"此成果须在业务页面修改");
        var tool=tools.find(task.getToolName()).orElseThrow();
        if(!tools.visibleTo(tool,ctx)) throw new BizException(403,"当前模式或权限不允许修改草稿");
        var raw=drafts.replace(task.getToolName(),gate.readPlan(task),fields);
        ctx=ctx.derive(task.getConversationId(),task.getStreamId()).withRunId(task.getRunId()).withWorkItem(task.getWorkItemId(),task.getWorkItemRevision());
        if(task.getWorkItemId()!=null) ctx=ctx.withAttempt(nodes.findById(task.getWorkItemId()).orElseThrow().getAttempt());
        if("draftPlanSections".equals(tool.name())) {
            var rerun=tool.execute(ctx,drafts.arguments(raw));if(rerun.plan()==null)throw new BizException(4400,"修订内容未能生成有效预览");
            var updated=rerun.plan();raw=new AgentActionPlan(null,updated.summary(),updated.total(),updated.items(),updated.skipped(),updated.detailLines(),updated.reversible(),null,drafts.preview(tool.name(),updated));
        }
        var checked=reviews.evaluate(ctx,tool,raw.preview());
        return gate.replaceDraft(artifactId,expected,raw,checked,ctx);
    }
}
