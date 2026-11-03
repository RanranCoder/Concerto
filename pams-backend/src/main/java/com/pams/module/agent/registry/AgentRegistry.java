package com.pams.module.agent.registry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.repository.AgentDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * Agent 注册表：能力注册与发现。
 *
 * <p>优先读 {@code agent_definition} 表（可配置）；表为空时回退到 {@link AgentCatalog} 内置默认。
 * 提供「按名称取定义」与「解析工具作用域」两个核心能力，供 {@code CoordinatorAgent} 与专家调度使用。
 */
@Slf4j
@Component
public class AgentRegistry {

    private final AgentDefinitionRepository repository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentRegistry(AgentDefinitionRepository repository) {
        this.repository = repository;
    }

    /** 启用的 Agent 定义（按 sort_order）。表为空时回退内置默认。 */
    public List<AgentDefinition> enabled() {
        List<AgentDefinition> rows = repository.findByEnabledOrderBySortOrderAsc(1);
        if (!rows.isEmpty() || repository.count()>0) {
            return rows;
        }
        return AgentCatalog.defaults().stream().map(AgentRegistry::toTransient).toList();
    }

    public Optional<AgentDefinition> byName(String name) {
        return enabled().stream().filter(d -> d.getName().equals(name)).findFirst();
    }

    public boolean contains(String name) {
        return byName(name).isPresent();
    }

    /** Read-only migration report. Never overwrites custom prompts, scopes or disabled rows. */
    public List<PromptConfiguration> promptConfigurations() {
        return enabled().stream().map(def -> {
            var builtin=AgentCatalog.defaults().stream().filter(d->d.name().equals(def.getName())).findFirst();
            String role=def.getRolePrompt()==null?"":def.getRolePrompt();
            String status=builtin.isEmpty()?"CUSTOM_ROLE":
                    java.util.Objects.equals(role,builtin.get().rolePrompt())?"BUILTIN_UNVERSIONED":"CUSTOMIZED_PRESERVED";
            return new PromptConfiguration(def.getName(),status,
                    com.pams.module.agent.prompt.PromptResolver.resolve(com.pams.module.agent.prompt.PromptResolver.Id.CORE_POLICY).sha256(),
                    roleHash(role),toolScopeOf(def));
        }).toList();
    }
    public record PromptConfiguration(String agent,String roleStatus,String corePolicySha256,
                                      String roleSha256,List<String> configuredScope) {}
    private static String roleHash(String role) {
        try {return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(role.getBytes(java.nio.charset.StandardCharsets.UTF_8)));}
        catch(java.security.NoSuchAlgorithmException e) {throw new IllegalStateException(e);}
    }

    /** 解析工具作用域；返回 null 表示「不限」（全部可见工具）。 */
    public List<String> toolScopeOf(AgentDefinition def) {
        if (def == null) return List.of();
        if (AgentCatalog.COORDINATOR.equals(def.getName()) || AgentCatalog.REVIEWER.equals(def.getName())) return List.of();
        if (def.getToolScope() == null || def.getToolScope().isBlank()) return null;
        try {
            List<String> scope = objectMapper.readValue(def.getToolScope(), new TypeReference<List<String>>() {});
            return scope == null ? List.of() : scope;
        } catch (Exception e) {
            log.warn("解析 Agent 工具作用域失败：agent={} scope={}", def.getName(), def.getToolScope(), e);
            return List.of();
        }
    }

    private static AgentDefinition toTransient(AgentCatalog.Def d) {
        AgentDefinition e = new AgentDefinition();
        e.setName(d.name());
        e.setDisplayName(d.displayName());
        e.setDescription(d.description());
        e.setRolePrompt(d.rolePrompt());
        e.setEnabled(1);
        e.setSortOrder(0);
        // 工具作用域序列化交给持久化路径；这里回退定义直接用 List（不经 JSON）
        e.setToolScope(serialize(d.toolScope()));
        return e;
    }

    private static String serialize(List<String> scope) {
        if (scope == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < scope.size(); i++) {
            if (i > 0) {
                sb.append(",");
            }
            sb.append('"').append(scope.get(i)).append('"');
        }
        return sb.append("]").toString();
    }
}
