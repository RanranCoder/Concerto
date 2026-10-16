package com.pams.module.agent.config;

import com.pams.module.agent.llm.LlmProvider;
import com.pams.module.agent.llm.StubProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 「让应用自己读仓库根 .env」的接线契约（{@code spring.config.import}）。
 *
 * <p><b>为什么需要这条回归网</b>：没有它，把这几行删掉不会有任何测试变红 ——
 * 而后果是 IDE 与 {@code mvn spring-boot:run} 又回到"读不到 .env → 永远降级 stub"，
 * 表现为前端永远显示「规则模式」，且与密钥、网络都无关（这次排查就花了很久）。
 * 属于 D11 第 ③ 类"没有报错、只是功能悄悄消失"的静默失效，只能在配置层面锁。
 *
 * <p>两条断言各自守一件事：
 * <ol>
 *   <li>两个相对路径都在，且<b>每一条都必须是 {@code optional:}</b> ——
 *       缺文件就启动失败的话，docker/CI（它们直接注入环境变量、没有 .env）全都会起不来；</li>
 *   <li>测试档位把 provider 压成 stub —— 否则开发者机器上只要有 .env，
 *       {@code mvn test} 就会真的实例化 DeepSeekProvider，任何碰到 LLM 的测试都变成对外发请求
 *       （慢、花钱、结果不稳定，而且只在有 .env 的机器上复现）。</li>
 * </ol>
 *
 * <p>注意本测试<b>不依赖 .env 是否存在</b>：有它时验证"导入生效也不打真 API"，
 * 没有它时验证"缺失也照样起得来"。两种环境都必须绿。
 */
@SpringBootTest
@ActiveProfiles("test")
class EnvFileImportConfigTest {

    @Autowired
    Environment env;
    @Autowired
    AgentProperties props;
    @Autowired
    LlmProvider primaryProvider;

    @Test
    void envFileImportCoversBothWorkingDirectoriesAndStaysOptional() {
        String imports = env.getProperty("spring.config.import");

        assertThat(imports)
                .as("必须配置 .env 导入：IDE 的 Run Configuration 不读 .env，"
                        + "不导入就会永远降级成 stub 规则模式")
                .isNotNull();

        assertThat(imports)
                .as("CWD = 模块根（IDEA 默认、mvn 在 pams-backend 下跑）")
                .contains("optional:file:../.env[.properties]");
        assertThat(imports)
                .as("CWD = 仓库根（java -jar 从仓库根启动）")
                .contains("optional:file:./.env[.properties]");

        for (String part : imports.split(",")) {
            assertThat(part.trim())
                    .as("每一条都必须是 optional: —— 非 optional 会让没有 .env 的 docker/CI 起不来")
                    .startsWith("optional:");
        }
    }

    @Test
    void testsNeverUseTheRealProviderEvenWhenEnvFileExists() {
        String forced = System.getenv("PAMS_TEST_AGENT_PROVIDER");
        String expected = forced == null || forced.isBlank() ? "stub" : forced;

        assertThat(env.getProperty("pams.agent.provider"))
                .as("测试档位必须把 provider 压住（.env 被导入后也不许让测试打真实 API）")
                .isEqualTo(expected);
        if ("stub".equals(expected)) {
            assertThat(primaryProvider)
                    .as("默认必须是离线规则 provider")
                    .isInstanceOf(StubProvider.class);
        }
    }

    /**
     * 环境变量优先于 .env 导入（{@code AgentProperties.resolveApiKey} 先读 {@code System.getenv}）。
     *
     * <p>没设环境变量时用 JUnit 的 assumption 跳过而不是"静默通过"：CI 上没人设，
     * 那种情况下这条断言本来就没有可比的两份值（跳过会被如实记为 skipped，不会伪装成已验证）。
     */
    @Test
    void environmentVariableStillWinsOverTheImportedEnvFile() {
        String envKey = System.getenv("PAMS_AGENT_API_KEY");
        assumeTrue(envKey != null && !envKey.isBlank());

        assertThat(props.resolveApiKey())
                .as("同时存在环境变量与 .env 时必须取环境变量那份")
                .isEqualTo(envKey);
    }
}
