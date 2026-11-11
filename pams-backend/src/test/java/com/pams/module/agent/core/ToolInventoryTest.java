package com.pams.module.agent.core;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AssignableTypeFilter;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

/** Inspect actual component contracts without executing services or starting a database. */
class ToolInventoryTest {
    @Test void registeredToolContractsRemainExplicitAndProduceBaselineReport() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AssignableTypeFilter(AgentTool.class));
        var tools = new ArrayList<AgentTool>();
        for (var bean : scanner.findCandidateComponents("com.pams.module.agent.tool")) {
            var constructor = Class.forName(bean.getBeanClassName()).getDeclaredConstructors()[0];
            tools.add((AgentTool) constructor.newInstance(new Object[constructor.getParameterCount()]));
        }
        var registry = new ToolRegistry(tools);
        assertThat(registry.all()).hasSize(50);
        for(var definition:com.pams.module.agent.registry.AgentCatalog.defaults()) {
            if(definition.toolScope()!=null) for(String name:definition.toolScope())
                assertThat(registry.find(name)).as("default scope tool %s",name).isPresent();
        }
        assertThat(registry.all().stream().filter(AgentTool::supportsForm).map(AgentTool::name))
                .containsExactly("createActivity");
        for (var tool : registry.all()) {
            assertThat(tool.parametersSchema()).containsEntry("type", "object");
            assertThat(tool.descriptor().sensitivity()).isNotNull();
            assertThat(tool.descriptor().contractPolicy().verification()).as(tool.name()).doesNotContain("AUDIT_REMAINING");
        }
        var output = Path.of("target/agent-tool-inventory.json");
        Files.createDirectories(output.getParent());
        new com.fasterxml.jackson.databind.ObjectMapper().writerWithDefaultPrettyPrinter()
                .writeValue(output.toFile(), registry.all().stream().map(AgentTool::descriptor).toList());
        var specs=registry.all().stream().map(t->new com.pams.module.agent.llm.LlmProvider.ToolSpec(t.name(),t.description(),t.parametersSchema())).toList();
        var props=new com.pams.module.agent.config.AgentProperties();
        var ctx=new ToolContext(null,List.of(),null,null);
        var prompt=com.pams.module.agent.prompt.PromptRenderer.executor(ctx,registry.all(),null,4);
        var messages=List.of(com.pams.module.agent.llm.LlmProvider.ChatMessage.text("system",prompt),com.pams.module.agent.llm.LlmProvider.ChatMessage.text("user","读取活动1详情"));
        assertThatCode(()->new com.pams.module.agent.service.ContextBudget(props).fit(messages,specs)).as("47-tool menu must fit the default input window").doesNotThrowAnyException();
    }
}
