package com.pams.module.agent.orchestration;

import com.fasterxml.jackson.databind.*;
import com.pams.common.BizException;
import java.util.*;

/** Resolves only original-user literals and direct successful structured receipts. No model coercion. */
public final class ControlledBindings {
    private static final ObjectMapper JSON=new ObjectMapper().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private ControlledBindings(){}
    public static JsonNode tree(String value){try{return com.pams.module.agent.service.AgentJson.readTree(JSON,value);}catch(Exception e){throw new BizException(409,"结构化执行数据损坏");}}
    public static Map<String,Object> resolve(Map<String,Object> contract,Map<Integer,JsonNode> receipts) {
        JsonNode c=JSON.valueToTree(contract),schema=c.path("schema");var args=JSON.createObjectNode();
        for(var binding:c.path("inputBindings")) {
            String name=binding.path("parameter").asText();JsonNode value;
            if("USER".equals(binding.path("source").asText())) {
                String literal=binding.path("quote").asText();
                if(!c.path("goal").asText().contains(literal))throw new BizException(409,"原始目标引用失效");
                if("string".equals(schema.path("properties").path(name).path("type").asText()))value=JSON.getNodeFactory().textNode(literal);
                else try {value=JSON.readTree(literal);}catch(Exception e){throw new BizException(409,"输入不是所需类型，请补充明确参数");}
            } else if("STEP".equals(binding.path("source").asText())) {
                var receipt=receipts.get(binding.path("stepId").asInt());
                if(receipt==null)throw new BizException(409,"上游尚无可用结构化成功回执");
                String path=binding.path("path").asText();
                if(!path.matches("(?:payload|detail)(?:\\.[A-Za-z][A-Za-z0-9_]*|\\[[0-9]{1,4}\\]){1,12}"))throw new BizException(409,"结果路径不在业务数据范围");
                value=receipt;var matcher=java.util.regex.Pattern.compile("([A-Za-z][A-Za-z0-9_]*)|\\[([0-9]+)\\]").matcher(path);
                while(matcher.find())value=matcher.group(1)!=null?value.path(matcher.group(1)):value.path(Integer.parseInt(matcher.group(2)));
                if(value.isMissingNode() || value.isNull())throw new BizException(409,"上游结果缺少所需字段，不猜测参数");
            } else throw new BizException(409,"输入来源无效");
            args.set(name,value);
        }
        validate(schema,args,0);
        return JSON.convertValue(args,new com.fasterxml.jackson.core.type.TypeReference<Map<String,Object>>(){});
    }
    public static void validate(JsonNode schema,JsonNode value,int depth) {
        if(depth>12 || value==null || value.isNull())throw new BizException(409,"参数结构无效");
        String type=schema.path("type").asText();
        boolean valid=switch(type){case "object"->value.isObject();case "array"->value.isArray();case "string"->value.isTextual();case "integer"->value.isIntegralNumber()&&value.canConvertToLong();case "number"->value.isNumber();case "boolean"->value.isBoolean();default->false;};
        if(!valid)throw new BizException(409,"参数类型不符合实际工具契约");
        if(schema.has("enum")){boolean found=false;for(var allowed:schema.get("enum"))if(allowed.equals(value))found=true;if(!found)throw new BizException(409,"参数枚举无效");}
        if(value.isNumber() && (schema.has("minimum")&&value.decimalValue().compareTo(schema.get("minimum").decimalValue())<0 || schema.has("maximum")&&value.decimalValue().compareTo(schema.get("maximum").decimalValue())>0))throw new BizException(409,"参数超出范围");
        if(value.isTextual() && (value.asText().length()>16000 || schema.has("maxLength")&&value.asText().length()>schema.get("maxLength").asInt()))throw new BizException(409,"参数超过长度限制");
        if(value.isObject()) {
            for(var required:schema.path("required"))if(!value.has(required.asText()))throw new BizException(409,"缺少必要参数："+required.asText());
            var fields=value.fields();while(fields.hasNext()){var field=fields.next();var property=schema.path("properties").get(field.getKey());if(property==null)throw new BizException(409,"参数超出能力范围");validate(property,field.getValue(),depth+1);}
        }
        if(value.isArray()){if(value.size()>100 || schema.has("maxItems")&&value.size()>schema.get("maxItems").asInt())throw new BizException(409,"数组超过上限");for(var item:value)validate(schema.path("items"),item,depth+1);}
    }
}
