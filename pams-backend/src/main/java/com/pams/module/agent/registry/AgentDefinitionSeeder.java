package com.pams.module.agent.registry;

import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.repository.AgentDefinitionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 幂等种入内置 Agent 定义（按 name 判存，缺少才补）。
 * 只在缺失时插入，已存在的定义（可能已被运维修改）不覆盖。
 */
@Slf4j
@Component
public class AgentDefinitionSeeder implements ApplicationRunner {

    private final AgentDefinitionRepository repository;

    public AgentDefinitionSeeder(AgentDefinitionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int order = 0;
        int created = 0;
        for (AgentCatalog.Def d : AgentCatalog.defaults()) {
            if (repository.existsByName(d.name())) {
                repository.findByName(d.name()).ifPresent(existing -> upgradeUnmodifiedScope(existing,d));
                order++;
                continue;
            }
            AgentDefinition e = new AgentDefinition();
            e.setName(d.name());
            e.setDisplayName(d.displayName());
            e.setDescription(d.description());
            e.setRolePrompt(d.rolePrompt());
            e.setToolScope(toJson(d.toolScope()));
            e.setEnabled(1);
            e.setSortOrder(order++);
            e.setCreatedAt(LocalDateTime.now());
            e.setUpdatedAt(LocalDateTime.now());
            repository.save(e);
            created++;
        }
        if (created > 0) {
            log.info("多Agent：种入 {} 条内置 Agent 定义", created);
        }
    }

    private String toJson(java.util.List<String> scope) {
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

    /** Upgrade only known old defaults. Custom scopes and disabled definitions are preserved. */
    private void upgradeUnmodifiedScope(AgentDefinition existing, AgentCatalog.Def current) {
        var legacy = switch (current.name()) {
            case AgentCatalog.RETRIEVAL -> current.toolScope().stream().filter(n -> !n.equals("queryPartyMemberProgress")).toList();
            case AgentCatalog.PLANNING -> java.util.List.of("createActivity","draftPlanSections","generateAgendaFromPlan");
            case AgentCatalog.WRITING -> java.util.List.of("draftArticleFromActivity","draftNewsFromActivity","draftAnnouncement","publishAnnouncement");
            default -> null;
        };
        if (legacy == null) return;
        try {
            var old = new com.fasterxml.jackson.databind.ObjectMapper().readTree(existing.getToolScope());
            var expected = new com.fasterxml.jackson.databind.ObjectMapper().readTree(toJson(legacy));
            if (expected.equals(old)) {
                existing.setToolScope(toJson(current.toolScope()));
                existing.setUpdatedAt(LocalDateTime.now());
                repository.save(existing);
            }
        } catch (Exception ignored) { /* malformed or custom definitions require explicit diagnosis */ }
    }
}
