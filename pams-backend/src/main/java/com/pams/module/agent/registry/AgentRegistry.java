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
        if (!rows.isEmpty()) {
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

    /** 解析工具作用域；返回 null 表示「不限」（全部可见工具）。 */
    public List<String> toolScopeOf(AgentDefinition def) {
        if (def == null || def.getToolScope() == null || def.getToolScope().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(def.getToolScope(), new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("解析 Agent 工具作用域失败：agent={} scope={}", def.getName(), def.getToolScope(), e);
            return null;
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
        if (scope == null || scope.isEmpty()) {
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
