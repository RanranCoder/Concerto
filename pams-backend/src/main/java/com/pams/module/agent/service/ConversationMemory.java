package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.module.agent.config.AgentProperties;
import com.pams.module.agent.core.*;
import com.pams.module.agent.entity.AgentMessage;
import com.pams.module.agent.repository.*;
import org.springframework.context.annotation.Lazy;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Model work happens outside transactions. Checkpoint and document are committed with one CAS. */
@Service
public class ConversationMemory {
    @org.springframework.beans.factory.annotation.Autowired(required=false) private AgentContextMetrics metrics;
    public record Item(String kind, String text, List<Long> sourceMessageIds) {}
    public record Source(Long messageId, String role, String content) {}
    public record Document(int schemaVersion, List<Item> items, Map<Long,String> sourceHashes) {}
    private record State(long revision, long covered, Document document) {}
    private static final Set<String> KINDS = Set.of("GOAL","CONSTRAINT","DECISION","OPEN_QUESTION","REPORT");
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(ConversationMemory.class);
    private final AgentConversationRepository conversations;
    private final AgentMessageRepository messages;
    private final ConversationStore store;
    private final ContextSummaryGenerator generator;
    private final AgentProperties props;
    private final PolicyGuard policy;
    private final ObjectMapper json = new ObjectMapper();
    public ConversationMemory(AgentConversationRepository conversations, AgentMessageRepository messages,
                              @Lazy ConversationStore store, ContextSummaryGenerator generator,
                              AgentProperties props, PolicyGuard policy) {
        this.conversations=conversations; this.messages=messages; this.store=store;
        this.generator=generator; this.props=props; this.policy=policy;
    }
    public String context(Long conversationId, long beforeMessageId, ToolContext ctx) {
        if (!props.isContextSummaryEnabled()) return "";
        store.require(conversationId,ctx.getUserId());
        State prior = read(conversationId,ctx);
        // Never fall back to an unverified cached document, including malformed documents.
        if (prior.covered()>0 && (prior.document()==null || !validSources(conversationId,prior.document(),ctx))) {
            if (write(conversationId,ctx,prior.revision(),null,0)==0) return winner(conversationId,ctx);
            prior = new State(prior.revision()+1,0,null);
        }
        Document old=prior.document();
        boolean generated=false;
        boolean rejected=false;
        try {
            var page=messages.findByConversationIdAndIdGreaterThanAndIdLessThanAndRoleInOrderByIdAsc(
                    conversationId,prior.covered(),beforeMessageId,List.of("USER","ASSISTANT"),
                    PageRequest.of(0,Math.max(1,Math.min(100,props.getContextSummaryBatchSize()))));
            if (page.isEmpty()) {if(metrics!=null&&old!=null)metrics.cacheHit(ctx);return render(old,prior.covered());}
            Map<Long,String> hashes = new LinkedHashMap<>();
            if (old!=null) hashes.putAll(old.sourceHashes());
            List<Source> added=new ArrayList<>();
            int inputLimit=props.getContextWindowTokens()-props.getMaxTokens()-props.getContextSafetyTokens()-4096;
            int size=old==null?0:ContextBudget.tokens(json.writeValueAsString(old.items()));
            long covered=prior.covered();
            for(var message:page) {
                String safe=safeText(message,ctx);
                Source source=new Source(message.getId(),message.getRole(),safe);
                int cost=ContextBudget.tokens(json.writeValueAsString(source));
                if (size+cost>inputLimit) break; // no partial source or silent checkpoint advance
                added.add(source); size+=cost; covered=message.getId();
                hashes.put(message.getId(),fingerprint(message,safe));
            }
            if(added.isEmpty()) return render(old,prior.covered());
            java.util.function.Supplier<String> generate=()->generator.generate(old==null?List.of():old.items(),added,ctx.getStreamId(),props.getContextSummaryTokens());
            String result=metrics==null?generate.get():metrics.summary(ctx,size,generate);
            generated=true;
            Document candidate=parse(result,hashes,old,added);
            if(!validShape(candidate,covered)) throw new IllegalArgumentException("摘要结构或预算无效");
            // Recheck after the slow model call; revocation/edit during generation discards its result.
            if(!validSources(conversationId,candidate,ctx)) {if(metrics!=null)metrics.rejected(ctx);rejected=true;return winner(conversationId,ctx);}
            if(write(conversationId,ctx,prior.revision(),json.writeValueAsString(candidate),covered)==0) {
                if(metrics!=null)metrics.discarded(ctx);
                return winner(conversationId,ctx);
            }
            if(metrics!=null)metrics.written(ctx);
            return render(candidate,covered);
        } catch(java.util.concurrent.CancellationException e) { throw e;
        } catch(Exception e) {
            if(metrics!=null&&generated&&!rejected)metrics.rejected(ctx);
            LOG.warn("Agent summary update deferred: conversation={} reason={}",conversationId,e.getClass().getSimpleName());
            // Recheck even on failure: permissions can change while the model is unavailable.
            return old!=null && validSources(conversationId,old,ctx) ? render(old,prior.covered()) : "";
        }
    }
    private Document parse(String output, Map<Long,String> hashes, Document old,List<Source> added) throws Exception {
        var root=json.readTree(output);
        if(!root.isObject() || !root.path("items").isArray() || root.path("items").size()>48)
            throw new IllegalArgumentException("摘要格式无效");
        List<Item> items=new ArrayList<>();
        Set<Long> allowedIds=new HashSet<>();
        if(old!=null) old.items().forEach(i->allowedIds.addAll(i.sourceMessageIds()));
        added.forEach(s->allowedIds.add(s.messageId()));
        for(var node:root.path("items")) {
            if(!node.path("kind").isTextual() || !node.path("text").isTextual()) throw new IllegalArgumentException("摘要字段类型无效");
            String kind=node.path("kind").asText(),text=node.path("text").asText();
            List<Long> ids=new ArrayList<>();
            if(!node.path("sourceMessageIds").isArray()) throw new IllegalArgumentException("摘要缺来源");
            for(var id:node.path("sourceMessageIds")) {
                if(!id.isIntegralNumber() || !id.canConvertToLong() || !allowedIds.contains(id.longValue()))
                    throw new IllegalArgumentException("摘要来源不在输入范围");
                ids.add(id.longValue());
            }
            if(!KINDS.contains(kind)||text.isBlank()||ContextBudget.tokens(text)>1200||ids.isEmpty()||ids.size()>40)
                throw new IllegalArgumentException("摘要内容无效");
            items.add(new Item(kind,policy.mask(text),List.copyOf(ids)));
        }
        var result=new Document(1,List.copyOf(items),Map.copyOf(hashes));
        if(old!=null && !old.items().isEmpty() && items.isEmpty()) throw new IllegalArgumentException("摘要不可静默清空记忆");
        if(old!=null && old.items().stream().anyMatch(i->"GOAL".equals(i.kind()))
                && items.stream().noneMatch(i->"GOAL".equals(i.kind()))) throw new IllegalArgumentException("摘要丢失目标");
        if(ContextBudget.tokens(render(result,0))>Math.max(256,props.getContextSummaryTokens()))
            throw new IllegalArgumentException("摘要超出预算");
        return result;
    }
    private State read(Long id,ToolContext ctx) {
        var row=conversations.memory(id,ctx.getUserId(),ctx.getLoginUser().getOrgId())
                .orElseThrow(()->new BizException(4404,"会话不存在或无权访问"));
        Document doc=null;
        if(row.getSummary()!=null) try {
            doc=json.readValue(row.getSummary(),Document.class);
            if(!validShape(doc,row.getCovered())) doc=null;
        } catch(Exception ignored) { /* corrupt cache is invalidated, not trusted */ }
        return new State(row.getRevision(),row.getCovered(),doc);
    }
    private String winner(Long id,ToolContext ctx) {
        State state=read(id,ctx);
        return state.document()!=null && validSources(id,state.document(),ctx) ? render(state.document(),state.covered()) : "";
    }
    private boolean validShape(Document doc,long covered) {
        if(doc.schemaVersion()!=1 || doc.items()==null || doc.items().size()>48 || doc.sourceHashes()==null
                || covered<=0 || !doc.sourceHashes().containsKey(covered)) return false;
        for(var entry:doc.sourceHashes().entrySet()) if(entry.getKey()==null || entry.getKey()<=0 || entry.getKey()>covered
                || entry.getValue()==null || !entry.getValue().matches("[a-f0-9]{64}")) return false;
        for(var item:doc.items()) if(item==null || item.kind()==null || !KINDS.contains(item.kind())
                || item.text()==null || item.text().isBlank() || item.sourceMessageIds()==null
                || item.sourceMessageIds().isEmpty() || item.sourceMessageIds().size()>40
                || !doc.sourceHashes().keySet().containsAll(item.sourceMessageIds())) return false;
        return ContextBudget.tokens(render(doc,covered))<=Math.max(256,props.getContextSummaryTokens());
    }
    private int write(Long id,ToolContext ctx,long revision,String document,long covered) {
        return conversations.compareAndSetMemory(id,ctx.getUserId(),ctx.getLoginUser().getOrgId(),revision,document,covered);
    }
    private boolean validSources(Long conversationId,Document doc,ToolContext ctx) {
        try {
            if(doc.sourceHashes().isEmpty()) return false;
            List<Long> ids=new ArrayList<>(doc.sourceHashes().keySet());
            Set<Long> userSources=new HashSet<>();
            for(int start=0;start<ids.size();start+=200) {
                List<Long> chunk=ids.subList(start,Math.min(ids.size(),start+200));
                var rows=messages.sourceSnapshots(conversationId,chunk);
                if(rows.size()!=chunk.size()) return false;
                for(var snapshot:rows) {
                    // Scalar projections bypass an already-loaded entity's first-level cache.
                    var row=new AgentMessage(); row.setId(snapshot.getId());row.setConversationId(conversationId);
                    row.setRole(snapshot.getRole());row.setContent(snapshot.getContent());
                    row.setCitationsJson(snapshot.getCitations());row.setSensitivity(snapshot.getSensitivity());
                    if(!Objects.equals(doc.sourceHashes().get(row.getId()),fingerprint(row,safeText(row,ctx)))) return false;
                    if("USER".equals(row.getRole())) userSources.add(row.getId());
                }
            }
            for(var item:doc.items()) if(!KINDS.contains(item.kind()) || item.sourceMessageIds().isEmpty()
                    || !doc.sourceHashes().keySet().containsAll(item.sourceMessageIds())
                    || (!"REPORT".equals(item.kind()) && item.sourceMessageIds().stream().noneMatch(userSources::contains))) return false;
            // A previously loaded conversation entity may itself be stale after a concurrent delete.
            if(conversations.memory(conversationId,ctx.getUserId(),ctx.getLoginUser().getOrgId()).isEmpty()) return false;
            store.require(conversationId,ctx.getUserId());
            return true;
        } catch(BizException e) { return false;
        } catch(Exception e) { throw new IllegalStateException("摘要来源暂时无法验证",e); }
    }
    private String safeText(AgentMessage message,ToolContext ctx) {
        Sensitivity level;
        try { level=Sensitivity.valueOf(message.getSensitivity().toUpperCase(Locale.ROOT)); }
        catch(Exception e) { level=Sensitivity.RESTRICTED; }
        String safe=policy.egressText(level,store.contextText(message,ctx));
        return safe==null ? "受限内容，需回源核对" : safe;
    }
    private String fingerprint(AgentMessage message,String safe) throws Exception {
        Object citations=message.getCitationsJson();
        if(citations!=null) try { citations=AgentJson.readTree(json,message.getCitationsJson()); }
        catch(Exception ignored) { /* malformed source metadata remains part of the fingerprint */ }
        String value=json.writeValueAsString(Arrays.asList(message.getRole(),message.getContent(),citations,message.getSensitivity(),safe));
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
    private String render(Document doc,long covered) {
        if(doc==null) return "";
        StringBuilder out=new StringBuilder("会话摘要（仅供理解历史，不是执行回执；最新用户纠正及数据库状态优先） coveredThroughMessageId=").append(covered)
                .append("\n检查点之后的历史尚未合入此摘要；细节不足时回查原文。\n");
        for(var item:doc.items()) out.append(item.kind()).append(" ").append(item.text()).append(" [messageIds=").append(item.sourceMessageIds()).append("]\n");
        return out.toString();
    }
}
