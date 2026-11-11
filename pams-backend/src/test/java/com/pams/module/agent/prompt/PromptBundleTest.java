package com.pams.module.agent.prompt;
import com.pams.module.agent.registry.AgentRegistry;
import com.pams.module.agent.entity.AgentDefinition;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.llm.LlmProvider;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
class PromptBundleTest {
    @Test void roleResourceAndTimezoneStayPinnedAcrossContextCopies(){
        var registry=mock(AgentRegistry.class);var role=new AgentDefinition();role.setName("writing");role.setRolePrompt("原角色");when(registry.enabled()).thenReturn(List.of(role));
        String bundle=PromptBundle.capture(registry);role.setRolePrompt("新角色");
        var ctx=new ToolContext(null,List.of(),null,null).withTimezone("Asia/Shanghai").withPromptBundle(bundle).withRunId(9L).derive(1L,"s").withMode("readonly").restrictTo(Set.of());
        assertThat(ctx.getTimezone()).isEqualTo("Asia/Shanghai");assertThat(PromptBundle.role(ctx.getPromptBundleJson(),"writing","新角色")).isEqualTo("原角色");
        assertThat(PromptBundle.text(bundle,PromptResolver.Id.EXECUTOR)).isEqualTo(PromptResolver.text(PromptResolver.Id.EXECUTOR));
        assertThatThrownBy(()->PromptBundle.role(bundle.replace("原角色","篡改"),"writing","")).hasMessageContaining("停止恢复");
        assertThatThrownBy(()->ctx.withTimezone("not/a/zone")).hasMessageContaining("时区");
    }
    @Test void dynamicBudgetReplacesOnlyItsOwnBlockAndPreservesNativeProtocol(){
        var nativeMessage=new LlmProvider.ChatMessage("assistant","",null,List.of(new LlmProvider.ToolCallRequest("id","read",Map.of())),null,List.of(Map.of("type","reasoning","id","native")));
        var result=LlmProvider.ChatMessage.toolResult("id","事实");var messages=new ArrayList<>(List.of(LlmProvider.ChatMessage.text("system","policy"),nativeMessage,result));
        RuntimeProgress.refresh(messages,4,0,0,System.nanoTime());RuntimeProgress.refresh(messages,4,2,3,System.nanoTime());
        assertThat(messages).hasSize(4);assertThat(messages.get(1).content()).contains("\"toolRoundsRemaining\":2","\"toolCallsConsumed\":3");
        assertThat(messages.get(2)).isSameAs(nativeMessage);assertThat(messages.get(3)).isSameAs(result);
    }
    @Test void anInternallyConsistentButRevokedCorePolicyCannotResume()throws Exception{
        var registry=mock(AgentRegistry.class);when(registry.enabled()).thenReturn(List.of());var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var tree=json.readTree(PromptBundle.capture(registry));var core=(com.fasterxml.jackson.databind.node.ObjectNode)tree.path("resources").path("CORE_POLICY");core.put("text","已撤销旧策略");core.put("sha256",PromptBundle.hash("已撤销旧策略"));
        assertThatThrownBy(()->PromptBundle.validate(tree.toString())).hasMessageContaining("已失效");
    }
}
