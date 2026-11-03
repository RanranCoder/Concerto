package com.pams.module.agent.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.common.*;
import com.pams.module.agent.core.*;
import com.pams.module.agent.repository.AgentTaskRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import java.time.LocalDateTime;
import java.util.*;

/** Internal durable preparation/receipt store, used only inside each business service transaction. */
@Service
public class AgentPreparedOperations {
    public final ObjectMapper json=mapper();
    public static ObjectMapper mapper(){
        var dates=new com.fasterxml.jackson.databind.module.SimpleModule();
        dates.addSerializer(java.time.LocalDate.class,new com.fasterxml.jackson.databind.JsonSerializer<java.time.LocalDate>(){
            public void serialize(java.time.LocalDate value,com.fasterxml.jackson.core.JsonGenerator out,com.fasterxml.jackson.databind.SerializerProvider provider)throws java.io.IOException{out.writeString(value.toString());}});
        dates.addDeserializer(java.time.LocalDate.class,new com.fasterxml.jackson.databind.JsonDeserializer<java.time.LocalDate>(){
            public java.time.LocalDate deserialize(com.fasterxml.jackson.core.JsonParser input,com.fasterxml.jackson.databind.DeserializationContext context)throws java.io.IOException{return java.time.LocalDate.parse(input.getValueAsString());}});
        dates.addSerializer(java.time.LocalTime.class,new com.fasterxml.jackson.databind.JsonSerializer<java.time.LocalTime>(){
            public void serialize(java.time.LocalTime value,com.fasterxml.jackson.core.JsonGenerator out,com.fasterxml.jackson.databind.SerializerProvider provider)throws java.io.IOException{out.writeString(value.toString());}});
        dates.addSerializer(java.time.LocalDateTime.class,new com.fasterxml.jackson.databind.JsonSerializer<java.time.LocalDateTime>(){
            public void serialize(java.time.LocalDateTime value,com.fasterxml.jackson.core.JsonGenerator out,com.fasterxml.jackson.databind.SerializerProvider provider)throws java.io.IOException{out.writeString(value.toString());}});
        dates.addDeserializer(java.time.LocalTime.class,new com.fasterxml.jackson.databind.JsonDeserializer<java.time.LocalTime>(){
            public java.time.LocalTime deserialize(com.fasterxml.jackson.core.JsonParser input,com.fasterxml.jackson.databind.DeserializationContext context)throws java.io.IOException{return java.time.LocalTime.parse(input.getValueAsString());}});
        dates.addDeserializer(java.time.LocalDateTime.class,new com.fasterxml.jackson.databind.JsonDeserializer<java.time.LocalDateTime>(){
            public java.time.LocalDateTime deserialize(com.fasterxml.jackson.core.JsonParser input,com.fasterxml.jackson.databind.DeserializationContext context)throws java.io.IOException{return java.time.LocalDateTime.parse(input.getValueAsString());}});
        return new ObjectMapper().findAndRegisterModules().registerModule(dates)
                .enable(com.fasterxml.jackson.databind.DeserializationFeature.USE_LONG_FOR_INTS)
                .disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
    private final JdbcTemplate db;
    private final AgentTaskRepository tasks;
    public AgentPreparedOperations(JdbcTemplate db,AgentTaskRepository tasks){this.db=db;this.tasks=tasks;}
    public void guard(ToolContext ctx,String permission,boolean write) {
        if(ctx==null || ctx.getLoginUser()==null || !Objects.equals(OrgContext.get(),ctx.getLoginUser().getOrgId())
                || !ctx.hasPermission(permission) || write && (ctx.isReadonly() || ctx.isKb())) throw new BizException(403,"当前权限、组织或模式不允许该操作");
    }
    public String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception e){throw new IllegalStateException(e);}}
    public static LocalDateTime dateTime(Object value){
        if(value instanceof LocalDateTime date)return date;
        if(value instanceof java.sql.Timestamp timestamp)return timestamp.toLocalDateTime();
        return LocalDateTime.parse(value.toString().replace(' ','T'));
    }
    public Map<String,Object> decode(String value){try{return json.readValue(value,new TypeReference<Map<String,Object>>(){});}catch(Exception e){throw new BizException(409,"操作数据损坏");}}
    public String create(ToolContext ctx,String kind,Object payload) {
        String id=UUID.randomUUID().toString();
        db.update("INSERT INTO agent_business_operation(id,org_id,user_id,kind,payload_json,expires_at,created_at) VALUES(?,?,?,?,?,?,?)",
                id,ctx.getLoginUser().getOrgId(),ctx.getUserId(),kind,encode(payload),LocalDateTime.now().plusMinutes(15),LocalDateTime.now());return id;
    }
    public record Operation(String id,Map<String,Object> payload,WriteAgentTool.CommitResult result) {}
    public Operation require(ToolContext ctx,AgentActionPlan action,String kind,boolean commit) {
        if(action==null) throw new BizException(409,"确认清单无效");
        if(commit) {
            if(action.planId()==null) throw new BizException(403,"必须通过确认门禁提交");
            var task=tasks.findByIdAndUserId(action.planId(),ctx.getUserId()).orElseThrow(()->new BizException(404,"任务不可见"));
            if(!kind.equals(task.getToolName()) || !"RUNNING".equals(task.getStatus()) || !Objects.equals(task.getOrgId(),ctx.getLoginUser().getOrgId())) throw new BizException(403,"确认任务状态无效");
        }
        String id=null;
        for(String line:action.detailLines()==null?List.<String>of():action.detailLines()) if(line.startsWith("__args__:")) id=Objects.toString(decode(line.substring(9)).get("operationId"),null);
        if(id==null || !id.matches("[a-f0-9-]{36}")) throw new BizException(409,"操作标识无效；旧清单须在原生业务页面核对");
        var rows=db.queryForList("SELECT * FROM agent_business_operation WHERE id=? AND org_id=? AND user_id=? AND kind=?"+(commit?" FOR UPDATE":""),id,ctx.getLoginUser().getOrgId(),ctx.getUserId(),kind);
        if(rows.isEmpty()) throw new BizException(404,"操作不存在或不可见");var row=rows.getFirst();
        WriteAgentTool.CommitResult result=null;
        try{if(row.get("result_json")!=null) result=json.readValue(row.get("result_json").toString(),WriteAgentTool.CommitResult.class);}catch(Exception e){throw new BizException(409,"业务回执损坏");}
        if(commit && result==null && dateTime(row.get("expires_at")).isBefore(LocalDateTime.now())) throw new BizException(409,"预览已过期，请重新准备");
        return new Operation(id,decode(row.get("payload_json").toString()),result);
    }
    public void finish(String id,WriteAgentTool.CommitResult result){db.update("UPDATE agent_business_operation SET result_json=? WHERE id=?",encode(result),id);}
    public AgentActionPlan plan(String id,String summary,List<AgentActionPlan.PlanItem> items,String preview,String... lines) {
        var details=new ArrayList<>(Arrays.asList(lines));details.add("__args__:{\"operationId\":\""+id+"\"}");
        return new AgentActionPlan(null,summary,items.size(),items,List.of(),details,false,null,preview);
    }
}
