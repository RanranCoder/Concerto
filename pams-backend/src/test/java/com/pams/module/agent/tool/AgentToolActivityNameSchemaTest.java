package com.pams.module.agent.tool;

import com.pams.module.agent.core.AgentTool;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约测试：6 个「原本强制 activityId」的工具必须
 * ① schema 暴露 {@code activityName}；② {@code required} 不再包含 {@code activityId}。
 *
 * <p>为什么值得单独锁死：这 6 个工具的入参契约是 LLM 与工具的接口。
 * 一旦有人回退（例如又加回 required activityId），用户就会重新遇到
 * "Agent 非要我给活动 ID"的老问题，而编译、运行都不会报错——
 * 属 D11 第三类"语义变更"，必须有测试兜住。
 *
 * <p>本测试不启动 Spring、不连数据库：构造注入传 null 即可，
 * 因为 {@code parametersSchema()} / {@code required()} 是纯静态契约，不触碰依赖。
 */
class AgentToolActivityNameSchemaTest {

    private List<AgentTool> tools() {
        ActivityResolver resolver = new ActivityResolver(null);
        return List.of(
                new QueryRosterSigninStatsTool(null, resolver),
                new PreviewCreditBatchTool(null, null, resolver),
                new DraftArticleFromActivityTool(null, null, resolver),
                new CreateSeatLayoutTool(null, resolver),
                new GenerateActivityScheduleTool(null, resolver),
                new GenerateActivityTasksTool(null, resolver)
        );
    }

    @Test
    void allSixExposeActivityNameAndDropActivityIdRequired() {
        for (AgentTool t : tools()) {
            Map<String, Object> schema = t.parametersSchema();

            @SuppressWarnings("unchecked")
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");
            assertThat(props).as("工具 %s 的 schema.properties", t.name()).containsKey("activityName");

            @SuppressWarnings("unchecked")
            List<String> required = (List<String>) schema.get("required");
            assertThat(required == null ? List.<String>of() : required)
                    .as("工具 %s 的 required（不得回退强制 activityId）", t.name())
                    .doesNotContain("activityId");
        }
    }
}
