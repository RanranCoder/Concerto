package com.pams.common;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 守护不变量：真实密钥不得以字面量进入版本库配置（2026-09-22 审计 #10）。
 *
 * <p>曾发生：application.yml 把真实 DeepSeek key 写成 {@code ${PAMS_AGENT_API_KEY:sk-...}}
 * 的环境变量<b>默认值</b>，导致任何能读仓库/镜像的人可取走密钥，且部署忘设环境变量时
 * 生产一直在用仓库里这把 key。密钥只允许经环境变量注入，默认值必须为空
 * （与 jwt.secret 的 {@code ${random.uuid}} 治理口径一致）。
 */
class NoHardcodedSecretsTest {

    /** 与审计口径一致：sk- 开头 + 20 位以上字母数字的字面量密钥形态 */
    private static final Pattern LITERAL_KEY = Pattern.compile("sk-[A-Za-z0-9]{20,}");

    /** api-key 必须是「环境变量占位符 + 空默认值」，不允许任何字面量兜底 */
    private static final Pattern EMPTY_DEFAULT_API_KEY =
            Pattern.compile("(?m)^\\s*api-key:\\s*\\$\\{PAMS_AGENT_API_KEY:?\\}\\s*$");

    /**
     * 枚举 classpath 上所有 application(-profile).yml/.yaml/.properties，
     * 覆盖主配置与所有 profile 变体。
     */
    private List<Path> applicationConfigFiles() throws Exception {
        URL url = getClass().getResource("/application.yml");
        assertThat(url).as("classpath 中应存在 application.yml").isNotNull();
        Path dir = Paths.get(url.toURI()).getParent();
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(p -> p.getFileName().toString()
                            .matches("application(-[\\w-]+)?\\.(yml|yaml|properties)"))
                    .sorted()
                    .toList();
        }
    }

    @Test
    void applicationConfigs_mustNotContainLiteralSkKeys() throws Exception {
        for (Path file : applicationConfigFiles()) {
            String content = Files.readString(file);
            assertThat(LITERAL_KEY.matcher(content).find())
                    .as("%s 不得包含 sk- 形态的字面量密钥（密钥只经环境变量注入）",
                            file.getFileName())
                    .isFalse();
        }
    }

    @Test
    void apiKeyProperty_mustUseEnvVarWithEmptyDefault() throws Exception {
        Path main = Paths.get(getClass().getResource("/application.yml").toURI());
        String content = Files.readString(main);
        assertThat(EMPTY_DEFAULT_API_KEY.matcher(content).find())
                .as("pams.agent.api-key 必须形如 ${PAMS_AGENT_API_KEY:}（空默认值，禁止字面量兜底）")
                .isTrue();
    }
}
