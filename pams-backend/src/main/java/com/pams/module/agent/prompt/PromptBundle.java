package com.pams.module.agent.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.registry.AgentRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Stores only trusted prompt resources and configured roles, never conversation/source material. */
public final class PromptBundle {
    private static final ObjectMapper JSON=new ObjectMapper();
    private PromptBundle(){}
    public static void validate(String bundle){if(bundle!=null)try{verified(bundle);}catch(IllegalStateException e){throw new com.pams.common.BizException(409,"固定Prompt包已失效，请人工核对后重新发起");}}
    public static String capture(AgentRegistry agents){
        var resources=new LinkedHashMap<String,Object>();
        for(var id:PromptResolver.Id.values()){var p=PromptResolver.resolve(id);resources.put(id.name(),Map.of("text",p.text(),"sha256",p.sha256(),"version",p.version()));}
        var roles=new TreeMap<String,Object>();for(var role:agents.enabled()){String text=Objects.toString(role.getRolePrompt(),"");roles.put(role.getName(),Map.of("text",text,"sha256",hash(text)));}
        try{return JSON.writeValueAsString(Map.of("version","prompt-bundle/v1","resources",resources,"roles",roles));}catch(Exception e){throw new IllegalStateException(e);}
    }
    public static String text(String bundle,PromptResolver.Id id){
        if(bundle==null)return PromptResolver.text(id);
        var item=verified(bundle).path("resources").path(id.name());if(!item.path("text").isTextual())throw new IllegalStateException("固定Prompt包缺少所需资源");return item.path("text").asText();
    }
    public static String role(String bundle,String name,String fallback){
        if(bundle==null)return fallback;
        var roles=verified(bundle).path("roles");if(!roles.has(name))throw new IllegalStateException("固定Prompt包中没有该专家，请人工核对");return roles.path(name).path("text").asText();
    }
    private static com.fasterxml.jackson.databind.JsonNode verified(String value){
        try{
            var root=JSON.readTree(value);if(!"prompt-bundle/v1".equals(root.path("version").asText()))throw new IllegalStateException("不兼容的Prompt包");
            if(!root.path("resources").isObject()||!root.path("roles").isObject())throw new IllegalStateException("Prompt资源缺失");
            for(var resource:root.path("resources"))verify(resource);
            for(var item:root.path("roles"))verify(item);
            // Current mandatory policy can revoke old bundles. Do not execute obsolete safety rules.
            if(!PromptResolver.resolve(PromptResolver.Id.CORE_POLICY).sha256().equals(root.path("resources").path("CORE_POLICY").path("sha256").asText()))throw new IllegalStateException("公共策略版本已变化，旧任务须人工核对后迁移");
            return root;
        }catch(Exception e){throw new IllegalStateException("固定Prompt包不可用，停止恢复执行",e);}
    }
    private static void verify(com.fasterxml.jackson.databind.JsonNode item){if(!item.path("text").isTextual()||!hash(item.path("text").asText()).equals(item.path("sha256").asText()))throw new IllegalStateException("Prompt指纹不匹配");}
    static String hash(String text){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
}
