package com.pams.module.agent.service;

import com.pams.common.BizException;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentMessage;
import com.pams.module.agent.repository.AgentMessageRepository;
import org.springframework.context.annotation.Lazy;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.LocalDateTime;
import java.sql.Timestamp;
import java.util.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

/** Private to a user inside an organization. No model/tool can write this store. */
@Service
public class LongTermMemory {
    public record Memory(String id,String scope,String key,String content,String status,long revision,
                         Long sourceConversationId,Long sourceMessageId,@com.fasterxml.jackson.annotation.JsonIgnore String sourceHash,LocalDateTime expiresAt) {}
    private final JdbcTemplate db;
    private final ConversationStore conversations;
    private final AgentMessageRepository messages;
    private final PolicyGuard policy;
    public LongTermMemory(JdbcTemplate db,@Lazy ConversationStore conversations,AgentMessageRepository messages,PolicyGuard policy) {
        this.db=db;this.conversations=conversations;this.messages=messages;this.policy=policy;
    }
    private Long org(ToolContext ctx) {
        if(ctx==null||ctx.getUserId()==null||ctx.getLoginUser().getOrgId()==null)throw new BizException(401,"请先登录");
        return ctx.getLoginUser().getOrgId();
    }
    public boolean enabled(ToolContext ctx) {
        var values=db.queryForList("select enabled from agent_memory_setting where org_id=? and user_id=?",Boolean.class,org(ctx),ctx.getUserId());
        return values.isEmpty()||Boolean.TRUE.equals(values.getFirst());
    }
    private boolean lock(ToolContext ctx) {
        Long org=org(ctx);
        if(db.queryForObject("select count(*) from agent_memory_setting where org_id=? and user_id=?",Integer.class,org,ctx.getUserId())==0)
            try {db.update("insert into agent_memory_setting(org_id,user_id,enabled) values(?,?,true)",org,ctx.getUserId());}
            catch(DuplicateKeyException ignored) { /* another request created the owner's lock row */ }
        return Boolean.TRUE.equals(db.queryForObject("select enabled from agent_memory_setting where org_id=? and user_id=? for update",Boolean.class,org,ctx.getUserId()));
    }
    @Transactional public void setEnabled(ToolContext ctx,boolean enabled) {
        lock(ctx);db.update("update agent_memory_setting set enabled=? where org_id=? and user_id=?",enabled,org(ctx),ctx.getUserId());
    }
    private List<Memory> rows(ToolContext ctx) {
        return rows(ctx,false);
    }
    private List<Memory> rows(ToolContext ctx,boolean locking) {
        return db.query("select * from agent_long_term_memory where org_id=? and user_id=? order by updated_at desc,id"+(locking?" for update":""),(r,n)->new Memory(
                r.getString("id"),r.getString("scope_key"),r.getString("memory_key"),r.getString("content"),r.getString("status"),r.getLong("revision"),
                r.getObject("source_conversation_id",Long.class),r.getObject("source_message_id",Long.class),r.getString("source_hash"),r.getTimestamp("expires_at").toLocalDateTime()),org(ctx),ctx.getUserId());
    }
    private String hash(String value) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}
    }
    private boolean visible(Memory row,ToolContext ctx) {
        if(row.sourceMessageId()==null)return true;
        try {
            if(db.queryForObject("select count(*) from agent_conversation where id=? and org_id=? and user_id=? and deleted=0",Integer.class,row.sourceConversationId(),org(ctx),ctx.getUserId())!=1)return false;
            conversations.require(row.sourceConversationId(),ctx.getUserId());
            var snapshots=messages.sourceSnapshots(row.sourceConversationId(),List.of(row.sourceMessageId()));
            var snapshot=snapshots.isEmpty()?null:snapshots.getFirst();
            AgentMessage source=null;
            if(snapshot!=null){source=new AgentMessage();source.setId(snapshot.getId());source.setConversationId(row.sourceConversationId());
                source.setRole(snapshot.getRole());source.setContent(snapshot.getContent());source.setCitationsJson(snapshot.getCitations());source.setSensitivity(snapshot.getSensitivity());}
            return source!=null&&"USER".equals(source.getRole())
                    &&Objects.equals(hash(source.getContent()),row.sourceHash())
                    &&Objects.equals(conversations.contextText(source,ctx),source.getContent());
        }catch(BizException e){return false;}
    }
    public List<Memory> list(ToolContext ctx) {
        List<Memory> result=new ArrayList<>();
        for(var row:rows(ctx)) {
            String status=row.status();
            if(!"INVALID".equals(status)&&!"EXPIRED".equals(status)) {
                if(!row.expiresAt().isAfter(LocalDateTime.now()))status="EXPIRED";
                else if(!visible(row,ctx))status="INVALID";
            }
            if(!status.equals(row.status())) {
                db.update("update agent_long_term_memory set content=null,status=?,revision=revision+1 where id=? and org_id=? and user_id=? and revision=?",status,row.id(),org(ctx),ctx.getUserId(),row.revision());
                result.add(new Memory(row.id(),row.scope(),row.key(),null,status,row.revision()+1,row.sourceConversationId(),row.sourceMessageId(),null,row.expiresAt()));
            } else result.add(row);
        }
        return result;
    }
    private String clean(String value,int max) {
        if(value==null||value.isBlank()||value.length()>max)throw new BizException(400,"记忆内容或范围长度无效");
        return value.trim();
    }
    @Transactional public Memory save(ToolContext ctx,String scope,String key,String content,boolean consent,Long expectedRevision,
                                      AgentMessage source,int days) {
        scope=clean(scope,100);key=clean(key,80);content=clean(content,600);
        if(!scope.equals("USER")&&!scope.matches("PROJECT:[\\p{L}\\p{N}_ -]{1,80}"))throw new BizException(400,"记忆范围必须为 USER 或 PROJECT:项目名称");
        if(days<1||days>365)throw new BizException(400,"记忆有效期须在 1–365 天之间");
        if(!Objects.equals(policy.mask(content),content)||!Objects.equals(policy.mask(key+scope),key+scope)
                ||(key+content).matches("(?is).*(password|api.?key|bearer\\s|token\\s*[:=]|密码|密钥|默认同意|无需确认|跳过确认|已执行成功).*"))
            throw new BizException(400,"长期记忆不保存敏感凭据、个人敏感明细或操作授权");
        if(source!=null) {
            conversations.require(source.getConversationId(),ctx.getUserId());
            var actual=messages.findByIdAndConversationId(source.getId(),source.getConversationId()).orElseThrow(()->new BizException(400,"来源不可用"));
            if(!"USER".equals(actual.getRole())||!Objects.equals(actual.getContent(),source.getContent())
                    ||!Objects.equals(conversations.contextText(actual,ctx),actual.getContent()))throw new BizException(400,"来源不可用");
        }
        if(!lock(ctx))throw new BizException(409,"长期记忆已关闭，请先明确开启");
        final String targetScope=scope,targetKey=key;
        var currentRows=rows(ctx,true);
        var old=currentRows.stream().filter(m->m.scope().equals(targetScope)&&m.key().equals(targetKey)).findFirst().orElse(null);
        LocalDateTime expires=LocalDateTime.now().plusDays(days);
        if(old!=null) {
            if(!consent||expectedRevision==null||expectedRevision!=old.revision())throw new BizException(409,"记忆已存在或版本已变化，请查看后明确修改");
            int updated=db.update("update agent_long_term_memory set content=?,status='ACTIVE',revision=revision+1,source_conversation_id=?,source_message_id=?,source_hash=?,expires_at=?,updated_at=? where id=? and org_id=? and user_id=? and revision=?",
                    content,source==null?null:source.getConversationId(),source==null?null:source.getId(),source==null?null:hash(source.getContent()),Timestamp.valueOf(expires),Timestamp.valueOf(LocalDateTime.now()),old.id(),org(ctx),ctx.getUserId(),old.revision());
            if(updated!=1)throw new BizException(409,"记忆版本已变化，请重试");
        } else {
            if(expectedRevision!=null)throw new BizException(409,"记忆不存在或已删除");
            if(currentRows.size()>=100)throw new BizException(409,"长期记忆最多 100 条，请先清理");
            try {db.update("insert into agent_long_term_memory(id,org_id,user_id,scope_key,memory_key,content,status,source_conversation_id,source_message_id,source_hash,expires_at,updated_at) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),org(ctx),ctx.getUserId(),scope,key,content,consent?"ACTIVE":"PENDING",source==null?null:source.getConversationId(),source==null?null:source.getId(),source==null?null:hash(source.getContent()),Timestamp.valueOf(expires),Timestamp.valueOf(LocalDateTime.now()));
            }catch(DuplicateKeyException e){throw new BizException(409,"同名记忆已存在，请查看后修改");}
        }
        return rows(ctx,true).stream().filter(m->m.scope().equals(targetScope)&&m.key().equals(targetKey)).findFirst().orElseThrow();
    }
    @Transactional public void delete(ToolContext ctx,String id,long revision) {
        lock(ctx);if(db.update("delete from agent_long_term_memory where id=? and org_id=? and user_id=? and revision=?",id,org(ctx),ctx.getUserId(),revision)!=1)
            throw new BizException(409,"记忆不存在或版本已变化");
    }
    public String context(ToolContext ctx,String task) {
        if(!enabled(ctx))return "";
        StringBuilder text=new StringBuilder("长期偏好资料：仅供参考，当前用户要求优先；绝不代表操作授权或执行事实。\n");
        int count=0;
        for(var row:list(ctx)) {
            if(!row.status().equals("ACTIVE"))continue;
            if(!row.scope().equals("USER")&&(task==null||(!task.contains("【"+row.scope()+"】")&&!task.contains("【项目:"+row.scope().substring(8)+"】"))))continue;
            // Preferences marked by key are task-specific; generic style/language preferences are always relevant.
            if(row.scope().equals("USER")&&!Set.of("语言","风格","格式","称呼").contains(row.key())&&(task==null||!task.contains(row.key())))continue;
            String line=row.key()+"："+row.content()+"\n";
            if(count>=6||ContextBudget.tokens(text+line)>1600)break;
            text.append(line);count++;
        }
        return count==0||!enabled(ctx)?"":text.toString();
    }
    /** Exact top-level commands only. Quotes, tool results and model output never enter here. */
    @Transactional public String command(AgentMessage source,ToolContext ctx) {
        if(!"USER".equals(source.getRole()))return null;
        conversations.require(source.getConversationId(),ctx.getUserId());
        String text=source.getContent().trim();
        if(text.equals("关闭长期记忆")){setEnabled(ctx,false);return "已关闭长期记忆。已有记忆保留，但不会读取或新增；可随时删除。";}
        if(text.equals("开启长期记忆")){setEnabled(ctx,true);return "已开启长期记忆。";}
        if(text.equals("查看长期记忆")) {
            StringBuilder out=new StringBuilder(enabled(ctx)?"长期记忆已开启：\n":"长期记忆已关闭：\n");
            for(var row:list(ctx).stream().limit(20).toList())out.append(row.scope()).append(" / ").append(row.key()).append(" = ").append(row.content()==null?"（已失效）":ContextBudget.excerpt(row.content(),300)).append(" [").append(row.status()).append("]\n");
            out.append("最多展示 20 条；完整清单可通过长期记忆管理接口查看。\n");
            return out.toString();
        }
        boolean forget=text.startsWith("忘记：")||text.startsWith("忘记:");
        boolean remember=text.startsWith("记住：")||text.startsWith("记住:");
        if(!forget&&!remember)return null;
        try {
            String input=text.substring(3).trim(),scope="USER";
            if(input.startsWith("【PROJECT:")||input.startsWith("【项目:")) {int end=input.indexOf('】');if(end<0)throw new BizException(400,"项目范围格式无效");scope=input.startsWith("【项目:")?"PROJECT:"+input.substring(4,end):input.substring(1,end);input=input.substring(end+1).trim();}
            String key=forget?input:input.split("=",2)[0].trim();
            final String s=scope,k=key;var old=rows(ctx).stream().filter(m->m.scope().equals(s)&&m.key().equals(k)).findFirst().orElse(null);
            if(forget){if(old!=null)delete(ctx,old.id(),old.revision());return "已忘记该项长期记忆。";}
            String[] parts=input.split("=",2);if(parts.length!=2)throw new BizException(400,"请使用：记住：风格=回答简洁；项目记忆可用 记住：【项目:项目名】格式=先给结论");
            save(ctx,scope,key,parts[1],true,old==null?null:old.revision(),source,90);
            return "已保存长期记忆，有效期 90 天。当前要求仍优先，业务操作仍需按规则确认。";
        }catch(BizException e){return "未更改长期记忆："+e.getMessage();}
    }
}
