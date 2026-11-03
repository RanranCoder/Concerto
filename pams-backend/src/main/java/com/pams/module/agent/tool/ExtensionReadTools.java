package com.pams.module.agent.tool;
import com.pams.module.agent.core.*;
import com.pams.module.agent.service.AgentExtensionReadService;
import org.springframework.stereotype.Component;
import java.util.*;

public final class ExtensionReadTools {
    private ExtensionReadTools(){}
    abstract static class Read implements AgentTool {
        final AgentExtensionReadService service;Read(AgentExtensionReadService service){this.service=service;}
        public Sensitivity sensitivity(){return Sensitivity.NORMAL;}public String runningLabel(){return "正在"+label()+"…";}
        public List<String> requiredPermissions(){return name().equals("queryAnnouncements")||name().equals("searchKnowledgeEvidence")||name().equals("readKnowledgeEvidence")?List.of():name().equals("queryActivitySchedules")?List.of("activity:view","schedule:view"):List.of("activity:view");}
        public Map<String,Object> parametersSchema(){return name().equals("searchKnowledgeEvidence")?ToolArgs.schema("object",ToolArgs.map("query",ToolArgs.prop("string","明确检索词")),List.of("query")):ToolArgs.schema("object",ToolArgs.map("activityId",ToolArgs.prop("integer","活动ID"),"announcementId",ToolArgs.prop("integer","公告ID，仅查公告详情时"),"page",ToolArgs.prop("integer","从0开始，20条每页")),name().equals("queryAnnouncements")?List.of():List.of("activityId"));}
        public ToolResult execute(ToolContext ctx,Map<String,Object> args){try{return service.read(name(),ctx,args);}catch(com.pams.common.BizException e){return ToolResult.failed(e.getMessage());}}
    }
    @Component public static class Agenda extends Read {public Agenda(AgentExtensionReadService service){super(service);}public String name(){return "queryActivityAgenda";}public String label(){return "查询活动议程";}public String description(){return "分页读取明确活动的已登记议程顺序、标题与备注，区别于文档里的建议议程；只读。";}}
    @Component public static class Scores extends Read {public Scores(AgentExtensionReadService service){super(service);}public String name(){return "queryActivityScores";}public String label(){return "查询评分汇总";}public String description(){return "只读明确活动的评分规则和已录入评分数据库汇总，不提供人员评分明细，不推断缺失得分；不修改考核。";}}
    @Component public static class Schedules extends Read {public Schedules(AgentExtensionReadService service){super(service);}public String name(){return "queryActivitySchedules";}public String label(){return "查询活动排班";}public String description(){return "分页查询明确活动已登记班次与真实状态，不含人员明细。DRAFT不等于PUBLISHED，不推断课表未知人员空闲。";}}
    @Component public static class Announcements extends Read {public Announcements(AgentExtensionReadService service){super(service);}public String name(){return "queryAnnouncements";}public String label(){return "查询组织公告";}public String description(){return "分页查询当前组织公告标题/确认版本；指定announcementId读取正文，不标记已读或确认。公告与个人通知收件箱分开。";}}
    @Component public static class Evidence extends Read {public Evidence(AgentExtensionReadService service){super(service);}public String name(){return "searchKnowledgeEvidence";}public String label(){return "检索知识证据";}public String description(){return "办事模式下检索当前授权知识证据，复用KnowledgeQuery回源鉴权和出网策略；返回有来源、范围和截断标记的片段，不冒充全文。知识问答模式仍使用独立KbPipeline。";}}
    @Component public static class EvidenceRead extends Read {public EvidenceRead(AgentExtensionReadService service){super(service);}public String name(){return "readKnowledgeEvidence";}public String label(){return "续读知识证据";}public String description(){return "按searchKnowledgeEvidence返回的evidenceRef续读同一证据块，offset从0开始，每页600字。句柄绑定用户、组织、会话，15分钟有效；逐次回源鉴权，内容变更须重新检索。不是来源文档全文。";}
        public Map<String,Object> parametersSchema(){return ToolArgs.schema("object",ToolArgs.map("evidenceRef",ToolArgs.prop("string","当前会话证据句柄"),"offset",ToolArgs.prop("integer","文字偏移")),List.of("evidenceRef"));}}
}
