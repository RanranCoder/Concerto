package com.pams.module.knowledge.service;

import com.pams.module.knowledge.source.KnowledgeSource;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 分块器：把源抽出的"段"切成大小合适的检索单元。
 *
 * <h3>为什么按"段"而不是按字数切</h3>
 * 中文制度文本靠条款标题区分语义。按固定字数切会把一条条款拦腰截断，
 * 于是"第二十六条说了什么"这个问题可能命中半条 —— 答案就残缺了。
 * 所以顺序是：源先按标题切成 {@link KnowledgeSource.Section}，
 * 本类只在**段内**做长度控制。
 *
 * <h3>参数取值与理由</h3>
 * <table>
 *   <tr><td>目标长度 300–500 字</td><td>太短丢上下文，太长稀释相关性（一个 2000 字的块命中一次，不代表整块都相关）</td></tr>
 *   <tr><td>重叠 70 字</td><td>防止关键句正好落在切点上被割断</td></tr>
 *   <tr><td>单文档 ≤ 500 块</td><td>防超大文档吃满索引、挤占检索配额</td></tr>
 * </table>
 */
@Component
public class KnowledgeChunker {

    /** 小于这个长度就不再切了（一个块也够看） */
    public static final int TARGET_MIN = 300;
    /** 超过这个长度开始切 */
    public static final int TARGET_MAX = 500;
    /** 相邻块的重叠字符数 */
    public static final int OVERLAP = 70;
    /** 单文档最多产出多少块 */
    public static final int MAX_CHUNKS_PER_DOC = 500;

    /**
     * 切分一篇文档。
     *
     * @return 顺序编号的块；文档无有效内容时返回空列表
     */
    public List<ChunkDraft> chunk(KnowledgeSource.KnowledgeDoc doc) {
        List<ChunkDraft> out = new ArrayList<>();
        if (doc == null || doc.sections() == null) return out;

        int seq = 0;
        for (KnowledgeSource.Section s : doc.sections()) {
            if (s == null || s.content() == null || s.content().isBlank()) continue;

            // 分块标题：段自己指定了就用它的（T2 源的唯一检索入口），否则继承文档标题。
            // 见 KnowledgeSource.Section#chunkTitle 的说明
            String title = s.chunkTitle() != null && !s.chunkTitle().isBlank()
                    ? s.chunkTitle() : doc.title();

            for (String piece : splitLongText(s.content().strip())) {
                if (out.size() >= MAX_CHUNKS_PER_DOC) return out;
                out.add(new ChunkDraft(seq++, title, s.path(), piece));
            }
        }
        return out;
    }

    /* ------------------------------------------------------------------ 段内切分 */

    /**
     * 段内按句子边界切，尽量靠近 {@link #TARGET_MIN} 但不超过 {@link #TARGET_MAX}。
     *
     * <p>为什么要"尽量长"而不是精确等长：句子被打断的代价（语义断裂）远高于
     * 块长略有波动的代价。
     */
    List<String> splitLongText(String text) {
        List<String> out = new ArrayList<>();
        if (text.length() <= TARGET_MAX) {
            out.add(text);
            return out;
        }

        List<String> sentences = sentences(text);
        StringBuilder buf = new StringBuilder();
        for (String sentence : sentences) {
            // 单句就超长（比如没有标点的长串）→ 硬切，否则会无限累积
            if (sentence.length() > TARGET_MAX) {
                if (buf.length() > 0) {
                    out.add(buf.toString().strip());
                    buf.setLength(0);
                }
                for (int i = 0; i < sentence.length(); i += TARGET_MAX - OVERLAP) {
                    out.add(sentence.substring(i,
                            Math.min(sentence.length(), i + TARGET_MAX)));
                }
                continue;
            }

            if (buf.length() + sentence.length() > TARGET_MAX) {
                String done = buf.toString().strip();
                if (!done.isEmpty()) out.add(done);
                // 重叠：把上一块的尾部带到下一块开头，避免关键句落在切点上
                buf.setLength(0);
                if (done.length() > OVERLAP) {
                    buf.append(done.substring(done.length() - OVERLAP));
                }
            }
            buf.append(sentence);
            // 达到目标长度就收一块（在不超过上限的前提下尽早收）
            if (buf.length() >= TARGET_MIN && buf.length() + OVERLAP > TARGET_MAX) {
                out.add(buf.toString().strip());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) {
            String last = buf.toString().strip();
            if (!last.isEmpty()) out.add(last);
        }
        return out;
    }

    /**
     * 断句。中文句读优先，同时保留换行作为一个软边界
     * （列表项常以换行分隔而句末无标点）。
     */
    private List<String> sentences(String text) {
        List<String> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            buf.append(c);
            boolean end = c == '。' || c == '！' || c == '？' || c == '；'
                    || c == '!' || c == '?' || c == ';' || c == '\n';
            if (end) {
                out.add(buf.toString());
                buf.setLength(0);
            }
        }
        if (buf.length() > 0) out.add(buf.toString());
        return out;
    }

    /** 一个待写入的分块 */
    public record ChunkDraft(int seq, String title, String path, String content) {}
}
