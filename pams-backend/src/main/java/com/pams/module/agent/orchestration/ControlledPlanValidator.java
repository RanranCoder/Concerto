package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.databind.*;
import com.pams.module.agent.core.AgentTool;
import java.util.*;

/** Structural/provenance validation only. Result paths and semantic adequacy are not execution authority. */
public final class ControlledPlanValidator {
    public record Capability(String agent,AgentTool tool) {}
    public record Step(int id,String agent,String capability,String operationKind,boolean requiresConfirmation,
                       String completionCriteria,List<Map<String,Object>> inputBindings) {}
    public record Validated(Blackboard plan,List<String> constraints,List<String> clarifications,List<Step> steps) {}
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ControlledPlanValidator(){}
    public static String key(String agent,String tool){return agent+":"+tool;}
    public static Validated validate(String content,String goal,Map<String,Capability> capabilities)throws Exception {
        if(content==null || content.length()>24000)throw new IllegalArgumentException("OUTPUT_BUDGET");
        var root=JSON.readTree(content);keys(root,Set.of("goal","constraints","clarifications","steps"));
        if(!goal.equals(text(root,"goal",4000)))fail("GOAL_CHANGED");
        var constraints=strings(root.get("constraints"),8,500);var questions=strings(root.get("clarifications"),3,300);
        var nodes=root.get("steps");if(nodes==null || !nodes.isArray() || nodes.size()>8 || questions.isEmpty()&&nodes.isEmpty() || !questions.isEmpty()&&!nodes.isEmpty())fail("PLAN_SHAPE");
        var bb=new Blackboard();bb.setGoal(goal);var steps=new ArrayList<Step>();
        for(var node:nodes){
            keys(node,Set.of("id","agent","capability","task","dependsOn","inputBindings","completionCriteria"));
            int id=positive(node.get("id"));String agent=text(node,"agent",80),tool=text(node,"capability",100);
            var selected=capabilities.get(key(agent,tool));if(selected==null)fail("CAPABILITY_NOT_AVAILABLE");
            var deps=node.get("dependsOn");if(deps==null || !deps.isArray() || deps.size()>8)fail("DEPENDENCIES");
            var dependencies=new ArrayList<Integer>();for(var dep:deps){int value=positive(dep);if(value==id || dependencies.contains(value))fail("DEPENDENCIES");dependencies.add(value);}
            var bindings=node.get("inputBindings");if(bindings==null || !bindings.isArray() || bindings.size()>16)fail("INPUT_BINDINGS");
            @SuppressWarnings("unchecked") var parameters=(Map<String,Object>)selected.tool().parametersSchema().getOrDefault("properties",Map.of());
            var sources=new ArrayList<Map<String,Object>>();var bound=new HashSet<String>();
            for(var binding:bindings){
                String parameter=text(binding,"parameter",100),source=text(binding,"source",10);
                if(!parameters.containsKey(parameter) || !bound.add(parameter))fail("PARAMETER_NOT_AVAILABLE");
                if("USER".equals(source)){
                    keys(binding,Set.of("parameter","source","quote"));String quote=text(binding,"quote",1000);if(!goal.contains(quote))fail("USER_SOURCE_NOT_FOUND");
                    sources.add(Map.of("parameter",parameter,"source",source,"quote",quote));
                }else if("STEP".equals(source)){
                    keys(binding,Set.of("parameter","source","stepId","path"));int from=positive(binding.get("stepId"));if(!dependencies.contains(from))fail("MISSING_DATA_DEPENDENCY");
                    String path=text(binding,"path",200);if(!path.matches("(?:payload|detail)(?:\\.[A-Za-z][A-Za-z0-9_]*|\\[[0-9]{1,4}\\]){1,12}"))fail("RESULT_PATH");
                    sources.add(Map.of("parameter",parameter,"source",source,"stepId",from,"path",path,"resultPathVerified",false));
                }else fail("INPUT_SOURCE");
            }
            Object required=selected.tool().parametersSchema().get("required");
            if(required instanceof Collection<?> fields)for(Object field:fields)if(!bound.contains(field.toString()))fail("REQUIRED_BINDING_MISSING");
            var item=new Blackboard.PlanItem();item.setId(id);item.setAgent(agent);item.setDependsOn(dependencies);
            item.setTask("用户目标："+goal+"\n本步骤："+text(node,"task",1000)+"\n建议能力："+tool+"\n完成条件："+text(node,"completionCriteria",500));bb.getPlan().add(item);
            steps.add(new Step(id,agent,tool,selected.tool().writeOperation()?"PREPARE":"READ",selected.tool().writeOperation(),text(node,"completionCriteria",500),List.copyOf(sources)));
        }
        WorkItemState.validate(bb.getPlan());return new Validated(bb,constraints,questions,List.copyOf(steps));
    }
    private static void keys(JsonNode node,Set<String> allowed){if(node==null || !node.isObject())fail("OBJECT_REQUIRED");node.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))fail("UNKNOWN_FIELD");});if(node.size()!=allowed.size())fail("MISSING_FIELD");}
    private static String text(JsonNode node,String key,int max){var v=node.get(key);if(v==null || !v.isTextual() || v.asText().isBlank() || v.asText().length()>max)fail("TEXT_FIELD");return v.asText();}
    private static int positive(JsonNode node){if(node==null || !node.isIntegralNumber() || !node.canConvertToInt() || node.intValue()<1)fail("STEP_ID");return node.intValue();}
    private static List<String> strings(JsonNode node,int max,int length){if(node==null || !node.isArray() || node.size()>max)throw new IllegalArgumentException("STRING_LIST");var result=new ArrayList<String>();for(var v:node){if(!v.isTextual() || v.asText().isBlank() || v.asText().length()>length)fail("STRING_LIST");result.add(v.asText());}return List.copyOf(result);}
    private static void fail(String reason){throw new IllegalArgumentException(reason);}
}
