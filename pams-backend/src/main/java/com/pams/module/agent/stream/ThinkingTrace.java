package com.pams.module.agent.stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 思维链暂存（按 streamId → round 分段）。
 *
 * <p><b>为什么需要它</b>：模型推理是<b>流式片段</b>（走 {@link AgentStreamEvent#THINKING_DELTA}
 * 实时展示），而落库发生在**整轮结束之后**（{@code AgentOrchestrator} 的 ⑧ 落库段）。
 * 两者之间需要一个"按流攒起来、收官时取一份 JSON"的暂存点，
 * 否则刷新页面后思维链就没了 —— 过程区的实时体验与历史回看必须是同一份数据。
 *
 * <p><b>为什么按 round 分段而不是存一大坨</b>：一次回答里 LLM 可能被调用多次
 * （工具调用轮、重试、降级），每轮的推理是独立的思考段。
 * 前端折叠态要显示"第几段、多长"，展开态要能逐段读，混成一坨就都做不了。
 *
 * <p><b>内存与脱敏边界</b>：
 * <ul>
 *   <li>只保留最近 {@value #MAX_STREAMS} 条流（LRU 淘汰），单段上限 {@value #MAX_STEP_CHARS} 字，
 *       单流最多 {@value #MAX_STEPS_PER_STREAM} 段 —— 流式暂存不能变成内存泄漏源</li>
 *   <li>本类**不做**脱敏判断：落库策略（敏感级不落思考原文）在
 *       {@code ConversationStore.save} 里统一执行，避免"两个地方各判一次"漂移</li>
 * </ul>
 */
@Slf4j
@Service
public class ThinkingTrace {

    /** 保留的流数量上限（LRU 淘汰最旧的流） */
    private static final int MAX_STREAMS = 200;
    /** 单段推理保留字符上限（超出截断，避免超长推理把 JSON 列与前端撑爆） */
    private static final int MAX_STEP_CHARS = 8000;
    /** 单条流的段数上限（防御：异常情况下 round 爆炸） */
    private static final int MAX_STEPS_PER_STREAM = 40;
    /** 单条流的旁白行数上限（TTFE 旁白 + 检索旁白 + 未来新增，40 行足够） */
    private static final int MAX_NOTES_PER_STREAM = 40;

    private final ObjectMapper mapper = new ObjectMapper();

    /** streamId → (round → 累积中的思考段)。访问一律走 synchronized(this) */
    private final Map<String, LinkedHashMap<Integer, MutableStep>> streams =
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, LinkedHashMap<Integer, MutableStep>> eldest) {
                    return size() > MAX_STREAMS;
                }
            };

    /** 落库用的一段思考（不可变快照） */
    public record Step(int round, String text, long startedAt, long endedAt) {
        public long durationMs() {
            return endedAt > startedAt ? endedAt - startedAt : 0L;
        }
    }

    /** streamId → 编排旁白行（TTFE「正在理解你的问题…」这类后端自述）。与推理分段分开存 */
    private final Map<String, List<MutableNote>> notes =
            new LinkedHashMap<>(16, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, List<MutableNote>> eldest) {
                    return size() > MAX_STREAMS;
                }
            };

    private static final class MutableNote {
        final String text;
        final long ts = System.currentTimeMillis();

        MutableNote(String text) {
            this.text = text;
        }
    }

    private static final class MutableStep {
        final int round;
        final StringBuilder text = new StringBuilder();
        long startedAt = System.currentTimeMillis();
        long endedAt;

        MutableStep(int round) {
            this.round = round;
        }
    }

    /** 开始一段推理（同一 streamId+round 重复调用是幂等的，保留首次开始时间） */
    public synchronized void begin(String streamId, int round) {
        if (streamId == null) return;
        stepsOf(streamId).computeIfAbsent(round, MutableStep::new);
    }

    /** 追加一段推理增量（原文全量进暂存；实时展示的合并由调用方负责） */
    public synchronized void append(String streamId, int round, String piece) {
        if (streamId == null || piece == null || piece.isEmpty()) return;
        MutableStep step = stepsOf(streamId).computeIfAbsent(round, MutableStep::new);
        if (step.text.length() >= MAX_STEP_CHARS) return;
        int room = MAX_STEP_CHARS - step.text.length();
        step.text.append(room >= piece.length() ? piece : piece.substring(0, room));
    }

    /** 结束一段推理（记结束时间；重复调用不覆盖首次结束时间） */
    public synchronized void end(String streamId, int round) {
        MutableStep step = find(streamId, round);
        if (step != null && step.endedAt == 0L) step.endedAt = System.currentTimeMillis();
    }

    /**
     * 丢弃一段推理：某次尝试中途失败时调用。
     *
     * <p>与正文流 {@code DeltaBatch.discard()} 同一个理由 —— 失败的那半截推理已经
     * 推给前端了，但重试会换 round 重发，留着它就会出现"两段半截推理"。
     */
    public synchronized void drop(String streamId, int round) {
        LinkedHashMap<Integer, MutableStep> m = streams.get(streamId);
        if (m != null) m.remove(round);
    }

    /** 取一段快照（测试与调试用） */
    public synchronized List<Step> steps(String streamId) {
        LinkedHashMap<Integer, MutableStep> m = streams.get(streamId);
        if (m == null || m.isEmpty()) return List.of();
        List<Step> out = new ArrayList<>(m.size());
        for (MutableStep s : m.values()) out.add(snapshot(s));
        return out;
    }

    /**
     * 落库用 JSON（{@code [{round,text,startedAt,endedAt,durationMs}]}）。
     *
     * @return 没有任何推理时返回 {@code null} —— 调用方据此不写列（而不是写一个空数组）
     */
    public synchronized String toJson(String streamId) {
        List<Map<String, Object>> payload = toList(streamId);
        if (payload.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("思维链序列化失败：stream={}", streamId, e);
            return null;
        }
    }

    /**
     * 思维链快照（可直接放进 REST 响应；与 {@link #toJson} 同源，避免两处拼装漂移）。
     *
     * <p>为什么要随 /chat 一起返回：本轮结束后它会变成"历史轮次"，
     * 前端那时只能靠消息上的数据回放 —— 只给 toolCalls 的话，思维链就在切换瞬间丢了。
     */
    public synchronized List<Map<String, Object>> toList(String streamId) {
        List<Map<String, Object>> payload = new ArrayList<>();
        for (Step s : steps(streamId)) {
            if (s.text() == null || s.text().isBlank()) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("round", s.round());
            m.put("text", s.text());
            m.put("startedAt", s.startedAt());
            m.put("endedAt", s.endedAt());
            m.put("durationMs", s.durationMs());
            payload.add(m);
        }
        return payload;
    }

    /** 流结束后释放（历史回看走落库数据，不再需要内存副本） */
    public synchronized void clear(String streamId) {
        if (streamId != null) {
            streams.remove(streamId);
            notes.remove(streamId);
        }
    }

    /**
     * 收集一条编排旁白（{@code THINKING} 事件）。
     *
     * <p>为什么要收：旁白此前只发事件、不落任何地方 —— 刷新后过程区里
     * 「正在理解你的问题…」这类行整段消失，用户看到的不是刚才那条时间轴。
     * 推理分段与旁白是两种语义，所以分开存、落下两个列。
     */
    public synchronized void note(String streamId, String text) {
        if (streamId == null || text == null || text.isBlank()) return;
        List<MutableNote> list = notes.computeIfAbsent(streamId, k -> new ArrayList<>());
        while (list.size() >= MAX_NOTES_PER_STREAM) list.remove(0);
        list.add(new MutableNote(text));
    }

    /**
     * 旁白快照（可直接进 REST 响应 / 落库）。
     *
     * <p>{@code durationMs} = **下一条旁白的 ts − 本条 ts**：旁白没有独立的结束事件，
     * 它"持续到下一句旁白/下一步"才是真实语义（例如「已找到 3 条相关依据，正在整理…」
     * 一直持续到答复出现）。最后一条取不到下一行，就**不给** durationMs
     * （给 0 会被前端渲染成"耗时 0ms"，比不给更误导）。
     */
    public synchronized List<Map<String, Object>> notesList(String streamId) {
        List<MutableNote> list = notes.get(streamId);
        if (list == null || list.isEmpty()) return List.of();
        List<Map<String, Object>> out = new ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            MutableNote cur = list.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("note", cur.text);
            m.put("ts", cur.ts);
            if (i + 1 < list.size()) {
                long next = list.get(i + 1).ts;
                if (next > cur.ts) m.put("durationMs", next - cur.ts);
            }
            out.add(m);
        }
        return out;
    }

    /** 旁白落库 JSON；没有任何旁白时返回 {@code null}（调用方据此不写列） */
    public synchronized String notesJson(String streamId) {
        List<Map<String, Object>> payload = notesList(streamId);
        if (payload.isEmpty()) return null;
        try {
            return mapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("编排旁白序列化失败：stream={}", streamId, e);
            return null;
        }
    }

    private LinkedHashMap<Integer, MutableStep> stepsOf(String streamId) {
        LinkedHashMap<Integer, MutableStep> m = streams.computeIfAbsent(streamId, k -> new LinkedHashMap<>());
        while (m.size() > MAX_STEPS_PER_STREAM) {
            Integer oldest = m.keySet().iterator().next();
            m.remove(oldest);
        }
        return m;
    }

    private MutableStep find(String streamId, int round) {
        if (streamId == null) return null;
        LinkedHashMap<Integer, MutableStep> m = streams.get(streamId);
        return m == null ? null : m.get(round);
    }

    private static Step snapshot(MutableStep s) {
        return new Step(s.round, s.text.toString(),
                s.startedAt, s.endedAt == 0L ? System.currentTimeMillis() : s.endedAt);
    }
}
