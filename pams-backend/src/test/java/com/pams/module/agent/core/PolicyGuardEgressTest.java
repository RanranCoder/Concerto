package com.pams.module.agent.core;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 出网闸口（PolicyGuard）的确定性回归 —— 对应权限审计 **缺陷 A2 / A6**。
 *
 * <p><b>这个 bug 的形状值得单独记一笔</b>：
 * 闸口被调用了、返回值也拿到了，但调用方只用它做了个 {@code null} 判断
 * （"是不是 restricted"），真正进 LLM 上下文的文本却取自别处（原文）。
 * 后果是 {@code SENSITIVE} 的脱敏<b>从未生效</b>，而
 * {@code RESTRICTED} 因为走 {@code null} 分支反而<b>正常拦截</b>——
 * 一半功能正常，一半功能空转，且<b>编译、运行、日志全都不报错</b>。
 *
 * <p>这正是 D11 第 ③ 类「语义变更静默失效」：
 * 代码看起来在做防护，实际什么都没做。属典型"测试不写就必然复发"的缺陷。
 *
 * <p>所以本测试钉两件事：
 * <ol>
 *   <li><b>行为契约</b>：三档语义（normal 直通 / sensitive 遮罩 / restricted 拦截）逐一断言，
 *       并特别断言 sensitive 的返回<b>确实与输入不同</b>（不是"算了一下就丢掉"）。</li>
 *   <li><b>接线契约</b>：编排器必须<b>真的使用</b>闸口的返回值。
 *       这条只能靠源码断言——行为测试覆盖不到"调用了但没用"这种缺陷，
 *       因为从外部看，两者产生的文本不一样，但都在"合法返回值"范围内。</li>
 * </ol>
 */
class PolicyGuardEgressTest {

    private final PolicyGuard guard = new PolicyGuard();

    /* ------------------------------------------------------------ ① 行为契约 */

    @Test
    void restrictedNeverEgresses() {
        String raw = "某党员 13800001234 的发展材料";

        assertThat(guard.egressText(Sensitivity.RESTRICTED, raw))
                .as("restricted 必须返回 null（由调用方改走模板话术）")
                .isNull();

        assertThat(guard.egress(Sensitivity.RESTRICTED, ToolResult.ok(raw)))
                .as("egress(ToolResult) 形态同样必须拦截")
                .isNull();
    }

    @Test
    void sensitiveMasksPhoneIdCardAndStudentNo() {
        String raw = "联系电话 13812345678，身份证 11010119900307721X，学号 2023123456";

        String out = guard.egressText(Sensitivity.SENSITIVE, raw);

        assertThat(out).isNotNull();
        assertThat(out).doesNotContain("13812345678");
        assertThat(out).doesNotContain("11010119900307721X");
        assertThat(out).doesNotContain("2023123456");
        assertThat(out).contains("138****5678");
        assertThat(out).contains("110101********721X");
        assertThat(out).contains("2023****56");
    }

    /**
     * 规则顺序回归 —— 本条由写测试时实际发现的缺陷倒推而来。
     *
     * <p>原实现先跑手机号规则。身份证 {@code 11010119900307721X} 里的
     * {@code 19900307721} 恰好满足 {@code 1[3-9]\d\d{4}\d{4}}，于是被手机号规则先吃掉，
     * 结果身份证里不再有连续 8 位数字，**身份证规则再也匹配不上**，
     * 遮罩结果退化成 {@code 110101199****7721X}（前 6 位与尾部仍在）。
     *
     * <p>这类缺陷不会报错、不会抛异常，只是"遮得不够"，正是 D11 第 ③ 类的形状。
     * 所以单独钉一条，防止有人"顺手把顺序调回去"。
     */
    @Test
    void idCardIsMaskedBeforePhoneSoItIsNotPartlyEaten() {
        String raw = "11010119900307721X";

        String out = guard.mask(raw);

        assertThat(out)
                .as("身份证必须整体按 前6后4 遮罩，不能被手机号规则先咬掉一段")
                .isEqualTo("110101********721X");
    }

    /** 学号与手机号都是数字串，必须靠边界约束互不干扰 */
    @Test
    void studentNoAndPhoneDoNotInterfere() {
        String raw = "学号 2023123456 手机 13812345678";

        String out = guard.mask(raw);

        assertThat(out).contains("2023****56").contains("138****5678");
    }

    /**
     * 本方法就是 A2 的核心回归断言。
     *
     * <p>如果哪天有人把 {@code egressText} 的结果丢掉、改回读原文，
     * 那么"从闸口取到的文本"与"原文"又会相等 —— 这条会立刻变红。
     */
    @Test
    void sensitiveMaskIsActuallyAppliedNotJustComputed() {
        String raw = "手机号 13900001111";
        String viaGate = guard.egressText(Sensitivity.SENSITIVE, raw);

        assertThat(viaGate)
                .as("SENSITIVE 经闸口后必须与原文不同 —— 否则闸口就是空转（缺陷 A2）")
                .isNotEqualTo(raw)
                .doesNotContain("13900001111");
    }

    @Test
    void normalPassesThroughUnchanged() {
        String raw = "共 3 个活动，最近一次是主题党日";

        assertThat(guard.egressText(Sensitivity.NORMAL, raw)).isEqualTo(raw);
    }

    @Test
    void nullLevelFallsBackToNormal() {
        assertThat(guard.egressText(null, "abc")).isEqualTo("abc");
    }

    @Test
    void nullTextForRestrictedStaysNull() {
        assertThat(guard.egressText(Sensitivity.RESTRICTED, null)).isNull();
        assertThat(guard.egress(Sensitivity.RESTRICTED, null)).isNull();
        assertThat(guard.egress(Sensitivity.NORMAL, null)).isNull();
    }

    /* ------------------------------------------------------------ ② 姓名遮罩（A6） */

    @Test
    void maskNameKeepsFirstCharOnly() {
        assertThat(guard.maskName("张三")).isEqualTo("张*");
        assertThat(guard.maskName("欧阳明")).isEqualTo("欧**");
        assertThat(guard.maskName("李")).as("单字姓名无处可遮，原样返回").isEqualTo("李");
        assertThat(guard.maskName(null)).isNull();
        assertThat(guard.maskName("  ")).isEqualTo("  ");
    }

    /**
     * 闸口<b>不该</b>承担姓名遮罩 —— 中文姓名无法用正则可靠识别，硬做会把正常文本改坏。
     *
     * <p>这条测试把"分工"固定下来：闸口管<b>模式</b>（证件号/手机号），
     * 调用点管<b>语义</b>（哪段文字是人名，见 {@code QueryPartyMemberProgressTool}）。
     * 若哪天有人往 {@code mask()} 里塞姓名正则，这条会提醒他先想清楚职责边界。
     */
    @Test
    void gateDoesNotGuessNames() {
        String raw = "共 3 条记录，涉及 张三、李四。";

        assertThat(guard.egressText(Sensitivity.SENSITIVE, raw))
                .as("闸口不做姓名识别（不猜）")
                .isEqualTo(raw);
    }

    /* ------------------------------------------------------------ ③ 接线契约（源码锁） */

    /**
     * 编排器必须真的把闸口返回值送进 LLM 上下文。
     *
     * <p>为什么用源码断言：这个缺陷的两种形态（用闸口 / 丢掉闸口）在运行期都能产出
     * "看起来正常"的文本，行为测试无法区分。而它一旦复发，唯一表现就是
     * <b>脱敏静默失效</b>—— 只能在代码层面锁。
     */
    @Test
    void orchestratorActuallyUsesTheGateResult() throws IOException {
        String src = readOrchestratorSource();

        assertThat(src)
                .as("编排器必须调用 policyGuard.egressText(...)（缺陷 A2 的接线点）")
                .contains("policyGuard.egressText(");

        assertThat(src)
                .as("不得把裸文本直接塞进 toolResult —— 必须经过闸口的返回值")
                .doesNotContain("LlmProvider.ChatMessage.toolResult(call.id(),\n"
                        + "                                text == null || text.isBlank() ? result.status() : text)");
    }

    private String readOrchestratorSource() throws IOException {
        String rel = "src/main/java/com/pams/module/agent/service/AgentOrchestrator.java";
        // surefire 的 working dir 是模块根目录，但 IDE 直跑时可能是仓库根 —— 两个都试
        for (String base : List.of("", "pams-backend/")) {
            Path p = Path.of(base + rel);
            if (Files.exists(p)) {
                return Files.readString(p, StandardCharsets.UTF_8);
            }
        }
        throw new IOException("找不到 AgentOrchestrator 源码，无法执行接线契约断言：" + rel);
    }

    /* ------------------------------------------- ④ 工具入参下钻的读侧闸门（V36） */

    /**
     * 入参下钻的闸门语义：只有 {@code normal} 才把「遮罩后的明文」下发给前端。
     *
     * <p>为什么闸门必须在读侧：{@code agent_tool_call.args_json} 是审计口径（永久保留），
     * 改它等于动审计；而"给用户看多少"是披露口径。两者分开之后，
     * 一次展示层的收紧不会顺手改掉审计数据。
     */
    @Test
    void argsAreOnlyExposedForNormalSensitivity() {
        String sanitized = "{\"name\":\"张三\",\"idCard\":\"***\"}";

        assertThat(guard.egressArgs(Sensitivity.NORMAL, sanitized))
                .as("normal 级工具：入参可下钻（值已遮罩）")
                .isEqualTo(sanitized);
        assertThat(guard.egressArgs(Sensitivity.SENSITIVE, sanitized))
                .as("sensitive 级：不下发入参（前端据此不给下钻入口）")
                .isNull();
        assertThat(guard.egressArgs(Sensitivity.RESTRICTED, sanitized))
                .as("restricted 级：不下发入参")
                .isNull();
        assertThat(guard.egressArgs(Sensitivity.NORMAL, null)).isNull();
        assertThat(guard.egressArgs(Sensitivity.NORMAL, "  ")).isNull();
    }

    /**
     * 接线契约：编排器的 {@code toolCalls[]} 元素必须带上**经闸口**的 argsJson。
     *
     * <p>锁两件事：①调用点存在；②argsJson 的取值来自闸口，而不是把
     * {@code auditService.toJson(args)} 直接塞进去（那会绕过敏感度判断，
     * 与"调用了但没用"是同一类缺陷）。
     */
    @Test
    void toolCallEntriesCarryGatedArgs() throws IOException {
        String src = readOrchestratorSource();

        assertThat(src)
                .as("toolCalls[] 必须调用 policyGuard.egressArgs(...)（V36 下钻闸门的接线点）")
                .contains("policyGuard.egressArgs(");
        assertThat(src)
                .as("argsJson 必须来自闸口返回值，不得直接下发未过闸的入参 JSON")
                .doesNotContain("m.put(\"argsJson\", auditService.toJson(args))");
    }
}
