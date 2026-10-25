package com.pams.module.agent.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.BizException;
import com.pams.module.agent.core.AgentActionPlan;
import org.springframework.stereotype.Component;
import java.util.*;

/** Converts the two editable content tools to explicit fields, keeping entity IDs server-owned. */
@Component
public class DraftContent {
    public static final Map<String,String> CHAPTERS=new LinkedHashMap<>();
    static {
        CHAPTERS.put("background","活动背景");CHAPTERS.put("purpose","活动目的");CHAPTERS.put("content","活动内容");
        CHAPTERS.put("notice","注意事项");CHAPTERS.put("emergency","应急预案");CHAPTERS.put("budget","经费预算");CHAPTERS.put("flow","活动流程");
    }
    private final HtmlSanitizer html;
    private final ObjectMapper json=new ObjectMapper();
    public DraftContent(HtmlSanitizer html) {this.html=html;}
    public boolean editable(String tool) {return Set.of("draftArticleFromActivity","draftPlanSections").contains(tool);}
    public Map<String,Object> arguments(AgentActionPlan plan) {
        for(String line:plan.detailLines()==null?List.<String>of():plan.detailLines()) if(line!=null && line.startsWith("__args__:")) {
            try {return AgentJson.readObject(json,line.substring(9));} catch(Exception e) {throw new BizException(4400,"草稿参数损坏，请重新生成");}
        }
        return Map.of();
    }
    public Map<String,String> fields(String tool,AgentActionPlan plan) {
        Map<String,String> result=new LinkedHashMap<>();
        if(!editable(tool)) return result;
        var args=arguments(plan);if(args.isEmpty()) return result;
        if("draftArticleFromActivity".equals(tool)) {
            result.put("title",Objects.toString(args.get("title"),""));result.put("content",plain(args.get("content")));
        } else for(String key:CHAPTERS.keySet()) result.put(key,plain(args.get(key)));
        return result;
    }
    public String preview(String tool,AgentActionPlan plan) {
        var fields=fields(tool,plan);if(fields.isEmpty()) return plan.preview();
        StringBuilder body=new StringBuilder();
        if("draftArticleFromActivity".equals(tool)) return "# "+fields.get("title")+"\n\n"+fields.get("content");
        for(var field:fields.entrySet()) if(!field.getValue().isBlank()) body.append("## ").append(CHAPTERS.get(field.getKey())).append("\n\n").append(field.getValue()).append("\n\n");
        return body.toString().strip();
    }
    public AgentActionPlan replace(String tool,AgentActionPlan original,Map<String,String> values) {
        var old=fields(tool,original);
        if(old.isEmpty() || values==null || !values.keySet().equals(old.keySet())) throw new BizException(4400,"请填写完整草稿字段，不能修改业务对象或提交参数");
        if(values.values().stream().anyMatch(v->v==null || v.length()>24000) || values.values().stream().mapToInt(String::length).sum()>64000)
            throw new BizException(4400,"草稿内容过长");
        if(values.values().stream().allMatch(String::isBlank) || values.containsKey("title") && (values.get("title").isBlank() || values.get("title").length()>200 || values.get("content").isBlank())) throw new BizException(4400,"标题或正文不能为空");
        var args=new LinkedHashMap<>(arguments(original));
        for(var entry:values.entrySet()) args.put(entry.getKey(),"title".equals(entry.getKey())?entry.getValue().strip():paragraphs(entry.getValue()));
        List<String> lines=new ArrayList<>(original.detailLines().stream().filter(l->!l.startsWith("__args__:")).toList());
        try {lines.add("__args__:"+json.writeValueAsString(args));} catch(Exception e) {throw new IllegalStateException(e);}
        var items=new ArrayList<AgentActionPlan.PlanItem>();
        for(var entry:values.entrySet()) if(!entry.getValue().isBlank()) items.add(new AgentActionPlan.PlanItem(null,"title".equals(entry.getKey())?"标题":CHAPTERS.getOrDefault(entry.getKey(),"正文"),entry.getValue().substring(0,Math.min(80,entry.getValue().length()))));
        var plan=new AgentActionPlan(null,original.summary(),original.total(),items,original.skipped(),lines,original.reversible(),null,original.preview());
        return new AgentActionPlan(null,plan.summary(),plan.total(),items,plan.skipped(),lines,plan.reversible(),null,preview(tool,plan));
    }
    private String plain(Object value) {
        if(value==null)return "";
        var document=org.jsoup.Jsoup.parse(value.toString());document.select("script,style").remove();
        document.select("br").before("\n");document.select("p,li,h1,h2,h3,h4,blockquote").after("\n\n");
        return document.body().wholeText().strip().replaceAll("\n{3,}","\n\n");
    }
    private String paragraphs(String text) {
        if(text.isBlank()) return "";
        String escaped=text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;");
        return "<p>"+escaped.replace("\r\n","\n").replace("\n\n","</p><p>").replace("\n","<br>")+"</p>";
    }
}
