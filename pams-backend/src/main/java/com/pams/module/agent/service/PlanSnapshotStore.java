package com.pams.module.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.module.agent.core.ToolContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.util.*;

/** Server-owned immutable source and contiguous read coverage, scoped to one user/conversation. */
@Service
public class PlanSnapshotStore {
    private final JdbcTemplate db;
    private final ObjectMapper json=new ObjectMapper();
    public PlanSnapshotStore(JdbcTemplate db) {this.db=db;}
    @org.springframework.scheduling.annotation.Scheduled(fixedDelay=600000,initialDelay=600000)
    @Transactional
    public void purgeExpired() {
        var now=LocalDateTime.now();
        db.update("DELETE FROM agent_plan_revision WHERE result_plan_id IS NULL AND snapshot_id IN (SELECT id FROM agent_plan_snapshot WHERE expires_at<?)",now);
        db.update("DELETE FROM agent_plan_snapshot WHERE expires_at<?",now);
    }
    public record Snapshot(String id,Long planId,String baseline,byte[] bytes,List<Map<String,Object>> blocks,
                           int covered,int total,boolean eligible,LocalDateTime expires) {}
    @Transactional
    public Snapshot record(ToolContext ctx,Long planId,String baseline,byte[] bytes,PlanDocumentReader.Document doc,int from,int to) {
        if(bytes.length>1024*1024 || doc.blocks().size()>500) return null;
        String id=PlanDocumentReader.hash(ctx.getUserId()+":"+ctx.getLoginUser().getOrgId()+":"+ctx.getConversationId()+":"+baseline+":"+(System.currentTimeMillis()/7_200_000));
        boolean eligible=false;
        if(doc.unsupportedElements().isEmpty()) {
            try {BudgetDocxRevision.validate(bytes);eligible=true;} catch(Exception ignored) { /* read remains available */ }
        }
        try {
            db.update("INSERT INTO agent_plan_snapshot(id,org_id,user_id,plan_id,conversation_id,baseline,source_base64,blocks_json,covered_through,total_blocks,eligible,expires_at) VALUES(?,?,?,?,?,?,?,?,0,?,?,?) ON DUPLICATE KEY UPDATE id=id",
                    id,ctx.getLoginUser().getOrgId(),ctx.getUserId(),planId,ctx.getConversationId(),baseline,
                    Base64.getEncoder().encodeToString(bytes),json.writeValueAsString(doc.blocks()),doc.blocks().size(),eligible,LocalDateTime.now().plusHours(2));
        } catch(com.fasterxml.jackson.core.JsonProcessingException e) {throw new IllegalStateException(e);}
        Snapshot snapshot=require(id,ctx,true);
        // Skipping ahead cannot establish full coverage. Duplicate/earlier pages are harmless.
        if(from<=snapshot.covered()) db.update("UPDATE agent_plan_snapshot SET covered_through=? WHERE id=?",Math.max(to,snapshot.covered()),id);
        return require(id,ctx,false);
    }
    public Snapshot require(String id,ToolContext ctx,boolean lock) {
        if(ctx==null || ctx.getLoginUser()==null || id==null) throw new BizException(404,"文档快照不存在或不可见");
        var rows=db.query("SELECT * FROM agent_plan_snapshot WHERE id=? AND org_id=? AND user_id=?"+(lock?" FOR UPDATE":""),
                (rs,n)-> {
                    try {return new Snapshot(rs.getString("id"),rs.getLong("plan_id"),rs.getString("baseline"),
                            Base64.getDecoder().decode(rs.getString("source_base64")),json.readValue(rs.getString("blocks_json"),new TypeReference<List<Map<String,Object>>>(){}),
                            rs.getInt("covered_through"),rs.getInt("total_blocks"),rs.getBoolean("eligible"),rs.getTimestamp("expires_at").toLocalDateTime());}
                    catch(Exception e) {throw new IllegalStateException("文档快照损坏",e);}
                },id,ctx.getLoginUser().getOrgId(),ctx.getUserId());
        if(rows.isEmpty()) throw new BizException(404,"文档快照不存在或不可见");
        Long conversation=db.queryForObject("SELECT conversation_id FROM agent_plan_snapshot WHERE id=?",Long.class,id);
        if(!Objects.equals(conversation,ctx.getConversationId())) throw new BizException(404,"文档快照不存在或不可见");
        var result=rows.getFirst();
        if(result.expires().isBefore(LocalDateTime.now())) throw new BizException(409,"文档快照已过期，请重新读取");
        return result;
    }
}
