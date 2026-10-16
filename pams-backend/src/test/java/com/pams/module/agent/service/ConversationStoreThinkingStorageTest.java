package com.pams.module.agent.service;

import com.pams.common.PageResult;
import com.pams.module.agent.core.Sensitivity;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.agent.entity.AgentMessage;
import com.pams.module.agent.repository.AgentMessageRepository;
import com.pams.security.LoginUser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.util.AopTestUtils;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 留存分档策略的落库回归网（V35/V36 的 {@code thinking_json} / {@code thinking_notes_json}）。
 *
 * <p><b>为什么必须真跑库，而不是只 mock 掉 repository</b>：这两列是 MySQL 的 <b>JSON 列</b>，
 * 而"敏感档只存摘要"最容易写错的方式恰恰是<b>截 JSON 文本</b>（截完就是非法 JSON，数据库直接拒绝，
 * 日志里只有一行报错）。mock 掉 repository 的话，这个错误会被完美地绕过去 ——
 * 测试全绿，生产写不进去。所以这里用 {@code @SpringBootTest} + H2 + 真实 repository，
 * {@code flush()+clear()} 后再从库里读回来，顺带验证 V35/V36 在 H2 上真的把两列建出来了。
 *
 * <p>覆盖三档语义：{@code normal} 原样、{@code sensitive} <b>两列都要在（截断后的摘要）</b>、
 * {@code restricted} 一个字都不落。旧实现在 sensitive 档是"整列不落"，
 * 于是同一轮里正文留了摘要、过程区整块空白 —— 那条回归就是这里第 ② 条断言守的。
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ConversationStoreThinkingStorageTest {

    private static final long CONV = 4242L;
    /** 与 {@code ConversationStore.THINKING_SENSITIVE_MAX} 同值的契约上限（写死在测试里，防止它被悄悄调大） */
    private static final int MAX = 500;
    /** 敏感档截断后缀（写死字面量：它是对前端的语义承诺"这段被截断了"，不能悄悄改没） */
    private static final String SUFFIX = "…（敏感档仅存摘要）";

    private static final String LONG_TEXT = "推".repeat(900);
    private static final String LONG_NOTE = "旁".repeat(700);
    private static final String SHORT_TEXT = "先看活动时间，再决定要不要建。";
    private static final String SHORT_NOTE = "正在理解你的问题…";

    @Autowired
    ConversationStore conversations;
    @Autowired
    AgentMessageRepository repository;
    @PersistenceContext
    EntityManager em;

    private final ObjectMapper mapper = new ObjectMapper();

    /* ------------------------------------------------------------ ① normal：原样 */

    @Test
    void normalKeepsBothColumnsVerbatim() {
        AgentMessage saved = saveAndReload(Sensitivity.NORMAL, reasoningJson(LONG_TEXT), notesJson(LONG_NOTE));

        assertThat(stored(saved.getThinkingJson()))
                .as("normal 档必须原样落库（含 900 字的推理原文）")
                .isEqualTo(reasoningJson(LONG_TEXT));
        assertThat(stored(saved.getThinkingNotesJson()))
                .as("normal 档旁白同样原样")
                .isEqualTo(notesJson(LONG_NOTE));
        assertThat(saved.getSensitivity()).isEqualTo("normal");
    }

    /* ------------------------------------------------- ② sensitive：两列都要在（截断后） */

    @Test
    void sensitiveKeepsBothColumnsAsTruncatedSummaries() {
        AgentMessage saved = saveAndReload(Sensitivity.SENSITIVE, reasoningJson(LONG_TEXT), notesJson(LONG_NOTE));

        assertThat(saved.getThinkingJson())
                .as("⚠️ sensitive 档两列**不能为 null**（旧实现整列不落 → 过程区空白）")
                .isNotNull();
        assertThat(saved.getThinkingNotesJson())
                .as("⚠️ 旁白列同理：不能因为敏感就整块丢掉")
                .isNotNull();

        String reasoning = textOf(stored(saved.getThinkingJson()), "text");
        assertThat(reasoning)
                .as("必须是原文前缀 + 显式截断标注，而不是别的什么")
                .isEqualTo(LONG_TEXT.substring(0, MAX) + SUFFIX);
        assertThat(reasoning.length()).isLessThanOrEqualTo(MAX + SUFFIX.length());
        assertThat(reasoning).startsWith(LONG_TEXT.substring(0, 100));

        String note = textOf(stored(saved.getThinkingNotesJson()), "note");
        assertThat(note).isEqualTo(LONG_NOTE.substring(0, MAX) + SUFFIX);
        assertThat(note.length()).isLessThanOrEqualTo(MAX + SUFFIX.length());

        assertThat(saved.getSensitivity()).isEqualTo("sensitive");
    }

    /** 短内容不能被"顺手截一刀"：没超上限就该一字不改（否则摘要会带着毫无意义的省略号） */
    @Test
    void sensitiveLeavesShortValuesUntouched() {
        AgentMessage saved = saveAndReload(Sensitivity.SENSITIVE, reasoningJson(SHORT_TEXT), notesJson(SHORT_NOTE));

        assertThat(textOf(stored(saved.getThinkingJson()), "text")).isEqualTo(SHORT_TEXT);
        assertThat(textOf(stored(saved.getThinkingNotesJson()), "note")).isEqualTo(SHORT_NOTE);
        assertThat(stored(saved.getThinkingJson())).doesNotContain(SUFFIX);
        assertThat(stored(saved.getThinkingNotesJson())).doesNotContain(SUFFIX);
    }

    /**
     * 防"改回截字符串"的那道闸：超长 text + 超长 note 之后，落库值必须<b>仍是合法 JSON</b>、
     * 仍是数组、元素仍带齐全部字段（{@code round}/{@code startedAt}/{@code endedAt}/{@code durationMs}）。
     *
     * <p>截字符串的实现会在这一条上直接失败：截断后的文本以半个 JSON 结尾，
     * 既解析不了，也丢掉了 {@code round} 等分段元数据（前端折叠态要显示"第几段 · 耗时"）。
     */
    @Test
    void sensitiveOutputStaysValidJsonWithEveryField() throws Exception {
        AgentMessage saved = saveAndReload(Sensitivity.SENSITIVE, reasoningJson(LONG_TEXT), notesJson(LONG_NOTE));

        String reasoning = stored(saved.getThinkingJson());
        assertThat(reasoning).as("落库值不能是被砍掉尾巴的 JSON 文本").endsWith("]");

        JsonNode arr = mapper.readTree(reasoning);
        assertThat(arr.isArray()).isTrue();
        assertThat(arr.size()).isEqualTo(1);
        JsonNode first = arr.get(0);
        assertThat(first.has("round")).as("分段元数据必须保留（前端靠它显示第几段）").isTrue();
        assertThat(first.path("round").asInt()).isZero();
        assertThat(first.has("startedAt")).isTrue();
        assertThat(first.has("endedAt")).isTrue();
        assertThat(first.has("durationMs")).isTrue();
        assertThat(first.has("text")).isTrue();

        JsonNode notes = mapper.readTree(stored(saved.getThinkingNotesJson()));
        assertThat(notes.isArray()).isTrue();
        assertThat(notes.get(0).has("note")).isTrue();
        assertThat(notes.get(0).has("ts")).as("旁白的时间戳不能丢（前端按它排序）").isTrue();
        assertThat(notes.get(0).path("ts").asLong()).isEqualTo(1730000000000L);
    }

    /* --------------------------------------------------------- ③ restricted：一个字都不落 */

    @Test
    void restrictedDropsBothColumns() {
        AgentMessage saved = saveAndReload(Sensitivity.RESTRICTED, reasoningJson(LONG_TEXT), notesJson(LONG_NOTE));

        assertThat(saved.getThinkingJson()).as("restricted 档不落推理").isNull();
        assertThat(saved.getThinkingNotesJson()).as("restricted 档不落旁白").isNull();
        assertThat(saved.getSensitivity()).isEqualTo("restricted");
        assertThat(saved.getContent())
                .as("正文同样只留占位话术（思维链与正文必须同一套策略）")
                .contains("受限");
    }

    /** 正文与思维链是同一套策略：sensitive 档正文也只存传入的摘要 */
    @Test
    void sensitiveAppliesTheSamePolicyToReplyText() {
        AgentMessage saved = saveAndReload(Sensitivity.SENSITIVE, "完整答复正文：张三是……", "摘要：敏感内容仅存摘要",
                reasoningJson(SHORT_TEXT), notesJson(SHORT_NOTE));

        assertThat(saved.getContent()).isEqualTo("摘要：敏感内容仅存摘要");
    }

    /* ------------------------------------------------------------------ 边界 */

    @Test
    void blankOrNullThinkingIsStoredAsNull() {
        for (Sensitivity level : Sensitivity.values()) {
            AgentMessage saved = saveAndReload(level, "   ", null);
            assertThat(saved.getThinkingJson()).as("%s + 空串 → null", level).isNull();
            assertThat(saved.getThinkingNotesJson()).as("%s + null → null", level).isNull();
        }
    }

    /**
     * 解析不了 / 不是数组 → 按"不落"处理，而不是把半截或非法内容写进去。
     *
     * <p>为什么不是"尽力而为地截一段"：这两列是 JSON 列，写非法 JSON 会被数据库拒绝；
     * 把非预期形态的内容硬塞进去，只会在前端回放时炸出更难查的问题。
     */
    @Test
    void sensitiveDropsUnparseableOrNonArrayJson() {
        assertThat(saveAndReload(Sensitivity.SENSITIVE, "{不是合法 JSON", null).getThinkingJson()).isNull();
        assertThat(saveAndReload(Sensitivity.SENSITIVE, "{\"round\":0}", null).getThinkingJson())
                .as("对象不是数组 → 不落（前端按数组消费）")
                .isNull();
        assertThat(stored(saveAndReload(Sensitivity.SENSITIVE, "[]", null).getThinkingJson()))
                .as("空数组原样保留（不代表敏感内容，只是没内容）")
                .isEqualTo("[]");
    }

    /* ============================================================ 读侧回归（五列 JSON 列） */

    /**
     * 五列 JSON 的<b>读侧</b>往返 —— 这条就是"H2 下读侧静默变空数组"的回归网。
     *
     * <p><b>为什么它必须存在</b>：写侧测试（上面那批）断言的是"列里存了什么"，
     * 而用户看到的是读侧组装出来的 VO。这两件事之间隔着一个 {@code readValue}：
     * 此前五处都是 {@code catch (Exception ignored)} 回落空数组 —— H2 的字符串包装形态必然抛异常，
     * 于是<b>工具行、引用、思维链、旁白、依据全都在 H2 下静默变空</b>，而写侧测试全绿。
     * 改之前这条必红，改之后它是防止回退的唯一屏障。
     *
     * <p>断言分两层：①五个字段都非空（防"静默丢内容"）；②思维链/旁白的元素结构完整
     * （防解析流程把 {@code round}/{@code ts} 这类元数据改形 —— 前端折叠态要靠它显示"第几段 · 耗时"）。
     */
    @Test
    void readSideReturnsAllFiveJsonColumns() {
        Long convId = conversations.create(1L, "读侧回归").getId();
        conversations.save(convId, AgentMessage.ROLE_ASSISTANT, "答复正文", Sensitivity.NORMAL, "摘要",
                List.of(Map.of("tool", "queryNotifications", "status", "OK", "durationMs", 12)),
                List.of(Map.of("type", "activity", "id", 42, "label", "测试活动")),
                "st-read-side", "deepseek", 12, "standard",
                List.of(Map.of("index", 1, "sourceType", "material", "sourceId", 7,
                        "snippet", "片段", "revoked", false)),
                reasoningJson(SHORT_TEXT), notesJson(SHORT_NOTE));
        em.flush();
        em.clear();

        Map<String, Object> vo = readAssistant(convId);

        assertThat(listOf(vo, "toolCalls")).as("tool_calls：读侧不能静默变空数组").hasSize(1);
        assertThat(first(listOf(vo, "toolCalls")).get("tool")).isEqualTo("queryNotifications");

        assertThat(listOf(vo, "references")).as("refs_json 同一个坑").hasSize(1);
        assertThat(first(listOf(vo, "references")).get("type")).isEqualTo("activity");

        assertThat(listOf(vo, "thinking")).as("thinking_json 同一个坑").hasSize(1);
        assertThat(listOf(vo, "thinkingNotes")).as("thinking_notes_json 同一个坑").hasSize(1);
        assertThat(listOf(vo, "citations")).as("citations_json 同一个坑").hasSize(1);

        // ② 结构完整：解析流程不能把元数据改形
        Map<String, Object> step = first(listOf(vo, "thinking"));
        assertThat(step).containsKeys("round", "text", "startedAt", "endedAt", "durationMs");
        assertThat(step.get("text")).isEqualTo(SHORT_TEXT);
        assertThat(((Number) step.get("startedAt")).longValue()).isEqualTo(1730000000000L);
        assertThat(((Number) step.get("endedAt")).longValue()).isEqualTo(1730000000500L);
        assertThat(((Number) step.get("durationMs")).longValue()).isEqualTo(500L);

        Map<String, Object> note = first(listOf(vo, "thinkingNotes"));
        assertThat(note).containsKeys("note", "ts");
        assertThat(note.get("note")).isEqualTo(SHORT_NOTE);
        assertThat(((Number) note.get("ts")).longValue())
                .as("旁白时间戳不能丢（前端按它排序）")
                .isEqualTo(1730000000000L);
    }

    /**
     * 坏数据只降级<b>那一列</b>，不牵连其余列、也不整条消息丢弃。
     *
     * <p>这条守的是 {@code readJsonList} 的容错边界：既不能"一列坏了抛异常炸掉整个历史列表"，
     * 也不能"坏了就当整列没内容地静默过去"（它会留一行 warn）。
     */
    @Test
    void readSideInvalidColumnDegradesOnlyThatColumn() {
        Long convId = conversations.create(1L, "读侧坏数据").getId();
        AgentMessage m = conversations.save(convId, AgentMessage.ROLE_ASSISTANT, "答复正文",
                Sensitivity.NORMAL, "摘要",
                List.of(Map.of("tool", "queryNotifications", "status", "OK")),
                List.of(Map.of("type", "activity", "id", 42)),
                "st-bad-json", "deepseek", 5, "standard",
                List.of(Map.of("index", 1, "sourceType", "material", "sourceId", 7)),
                reasoningJson(SHORT_TEXT), notesJson(SHORT_NOTE));
        // save() 会自己序列化，构造不出非法 JSON —— 只能直接往列里塞（模拟"列被写坏/形态异常"）
        m.setToolCalls("{不是数组");
        em.flush();
        em.clear();

        Map<String, Object> vo = readAssistant(convId);

        assertThat(listOf(vo, "toolCalls")).as("坏列回落空列表").isEqualTo(List.of());
        assertThat(listOf(vo, "references")).as("其余列不受影响：容错是逐列降级，不是整条消息丢").hasSize(1);
        assertThat(listOf(vo, "thinking")).hasSize(1);
        assertThat(listOf(vo, "thinkingNotes")).hasSize(1);
        assertThat(listOf(vo, "citations")).hasSize(1);
        assertThat(vo.get("content")).as("正文照常返回").isEqualTo("答复正文");
    }

    /** 思维链列写坏时同样只降级它自己 */
    @Test
    void readSideInvalidThinkingDegradesOnlyThinking() {
        Long convId = conversations.create(1L, "读侧坏思维链").getId();
        AgentMessage m = conversations.save(convId, AgentMessage.ROLE_ASSISTANT, "答复正文",
                Sensitivity.NORMAL, "摘要",
                List.of(Map.of("tool", "queryNotifications", "status", "OK")),
                List.of(Map.of("type", "activity", "id", 42)),
                "st-bad-thinking", "deepseek", 5, "standard", null,
                reasoningJson(SHORT_TEXT), notesJson(SHORT_NOTE));
        m.setThinkingJson("{不是数组");
        em.flush();
        em.clear();

        Map<String, Object> vo = readAssistant(convId);

        assertThat(listOf(vo, "thinking")).as("坏列回落空列表").isEqualTo(List.of());
        assertThat(listOf(vo, "thinkingNotes")).as("旁白是另一列，不受牵连").hasSize(1);
        assertThat(listOf(vo, "toolCalls")).hasSize(1);
        assertThat(listOf(vo, "references")).hasSize(1);
    }

    /**
     * 两种形态都能解出数组：MySQL 的"原文形态"与 H2 的"字符串再套一层"。
     *
     * <p>为什么直接测私有方法：这两种形态由<b>数据库</b>决定，测试里没法让同一个 H2 同时产出两者
     * （正常写入永远是包装形态）。而这段兼容逻辑恰恰是本次修复的核心，
     * 所以按入参形态直接钉死它 —— 面向上面的 DB 往返测试覆盖"包装形态真的能走通"，
     * 这里补"原文形态没被兼容逻辑弄坏"与"两次解不出就回落"。
     */
    @Test
    void readJsonListToleratesBothColumnShapes() throws Exception {
        // ⚠️ 必须拿**目标对象**而不是注入进来的那个引用：@Transactional 让 Spring 包了一层 CGLIB 代理，
        // 公共方法的字段访问会被拦截并转发到目标对象上，而**私有方法不会被拦截** ——
        // 直接在代理实例上反射调用会读到未初始化的字段（实测表现为 NPE 被 catch 吞掉 → 空列表，
        // 看起来像"兼容逻辑没生效"，其实是被测对象拿错了）。
        Object target = AopTestUtils.getTargetObject(conversations);

        Method readJsonList = ConversationStore.class
                .getDeclaredMethod("readJsonList", String.class, String.class);
        readJsonList.setAccessible(true);

        // H2 形态：JSON 被当成字符串值存，读回多一层引号与转义
        Object h2 = readJsonList.invoke(target, "\"[{\\\"round\\\":0}]\"", "test");
        assertThat((List<?>) h2).as("H2 的字符串包装要能再解一层").hasSize(1);

        // MySQL 形态：读回就是原文（未被包装）—— 修复不能把正常形态弄坏
        Object plain = readJsonList.invoke(target, "[{\"round\":0}]", "test");
        assertThat((List<?>) plain).as("未包装形态照旧").hasSize(1);

        // 两次都解不出 → 空列表（并留 warn），而不是抛异常
        assertThat((List<?>) readJsonList.invoke(target, "{不是数组", "test")).isEmpty();
        assertThat((List<?>) readJsonList.invoke(target, "\"{不是数组\"", "test")).isEmpty();
        assertThat((List<?>) readJsonList.invoke(target, "{\"a\":1}", "test"))
                .as("合法 JSON 但不是列表 → 空列表").isEmpty();
        assertThat((List<?>) readJsonList.invoke(target, "null", "test")).isEmpty();
        assertThat((List<?>) readJsonList.invoke(target, "   ", "test")).isEmpty();
        assertThat((List<?>) readJsonList.invoke(target, (Object) null, "test")).isEmpty();
    }

    /* ------------------------------------------------------------------ 工具 */
    /** 读侧组装：走公开的 messages(...)（toVo 是私有的，用户可见的就是这条路径） */
    private Map<String, Object> readAssistant(Long convId) {
        LoginUser lu = new LoginUser(1L, "reader", "读侧", "DIRECTOR", 4, null, null, 1L);
        ToolContext ctx = new ToolContext(lu, List.of(new SimpleGrantedAuthority("ROLE_DIRECTOR")),
                convId, "st-read-side", ToolContext.MODE_STANDARD);
        PageResult<Map<String, Object>> page = conversations.messages(convId, 1L, 1, 50, ctx);
        return page.getRecords().stream()
                .filter(vo -> AgentMessage.ROLE_ASSISTANT.equals(vo.get("role")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("读侧没有返回 assistant 消息"));
    }

    private List<?> listOf(Map<String, Object> vo, String field) {
        Object v = vo.get(field);
        assertThat(v).as("VO 字段 %s 必须存在且是列表", field).isInstanceOf(List.class);
        return (List<?>) v;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> first(List<?> list) {
        assertThat(list).as("列表不能为空").isNotEmpty();
        return (Map<String, Object>) list.get(0);
    }

    /**
     * 读出库里的思维链文本。
     *
     * <p><b>⚠️ 为什么需要这一层</b>：H2（测试 profile）与 MySQL 对 JSON 列的语义不同 ——
     * MySQL 把写入的文本解析成 JSON 文档，读回就是原文；H2 把它当 JSON <b>字符串值</b> 保存，
     * 读回会多一层引号与反斜杠转义（实测：{@code "[{\"round\":0,…}]"}）。
     * 这是数据库侧的环境差异、不是被测代码的行为，所以在测试边界剥掉它。
     *
     * <p>代价要说明白：H2 因此<b>不会</b>替我们拒绝"截字符串"产出的非法 JSON
     * （它当字符串照收），所以"写出合法 JSON"这条只能靠 {@code sensitiveOutputStaysValidJsonWithEveryField}
     * 里对产物的解析断言来守，而不能指望数据库报错。
     */
    private String stored(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            try {
                return mapper.readValue(s, String.class);
            } catch (Exception ignored) {
                return raw;
            }
        }
        return raw;
    }

    /**
     * 走真实的 14 参重载 → flush → clear → 从库读回。
     *
     * <p>{@code flush+clear} 是必须的：不清一级缓存的话 {@code findById} 会把内存里那个对象
     * 直接还给你，JSON 列到底能不能写入根本验证不到。
     */
    private AgentMessage saveAndReload(Sensitivity level, String thinkingJson, String notesJson) {
        return saveAndReload(level, "完整答复正文，可能很长……", "摘要", thinkingJson, notesJson);
    }

    private AgentMessage saveAndReload(Sensitivity level, String fullText, String summary,
                                       String thinkingJson, String notesJson) {
        AgentMessage saved = conversations.save(CONV, AgentMessage.ROLE_ASSISTANT, fullText, level, summary,
                null, null, "st-thinking-storage", "deepseek", 12, "standard", null,
                thinkingJson, notesJson);
        assertThat(saved.getId()).as("必须真的落库拿到了主键").isNotNull();

        em.flush();
        em.clear();
        return repository.findById(saved.getId()).orElseThrow();
    }

    /** 取数组第一个元素里某个字符串字段的值（顺带断言结构与字段存在） */
    private String textOf(String json, String field) {
        assertThat(json).as("列值不能为 null").isNotNull();
        try {
            JsonNode arr = mapper.readTree(json);
            assertThat(arr.isArray()).as("必须是数组：%s", json).isTrue();
            assertThat(arr.size()).isPositive();
            return arr.get(0).path(field).asText();
        } catch (Exception e) {
            throw new AssertionError("思维链列不是合法 JSON（截 JSON 文本的典型症状）：" + json, e);
        }
    }
    private static String reasoningJson(String text) {
        return "[{\"round\":0,\"text\":\"" + text + "\",\"startedAt\":1730000000000,"
                + "\"endedAt\":1730000000500,\"durationMs\":500}]";
    }

    private static String notesJson(String note) {
        return "[{\"note\":\"" + note + "\",\"ts\":1730000000000,\"durationMs\":123}]";
    }
}
