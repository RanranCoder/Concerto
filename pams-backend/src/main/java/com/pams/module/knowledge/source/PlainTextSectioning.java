package com.pams.module.knowledge.source;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 纯文本的标题切分（各源共用的启发式工具）。
 *
 * <p>为什么需要它：{@code KnowledgeChunker} 负责"把一段正文切成大小合适的块"，
 * 但它不知道**哪一行是标题** —— 那是内容的语义，只有源或源的结构注解知道。
 * 本工具补上这一层：把带标题层级感的纯文本切成 {@link KnowledgeSource.Section}，
 * 让引用的 {@code path}（"第三章 第二十六条"）成为可能。
 *
 * <p><b>为什么不用 markdown 解析器</b>：本系统里落入正文的富文本在写库前
 * 已经过 {@code HtmlSanitizer}，实际形态是"带换行的纯文本 + 中文编号"，
 * 不是 markdown。引一个解析器来猜反而不如直接理解中文编号。
 *
 * <p>做不到 100% 准确是**可以接受**的：切错的后果只是"分块的 path 不够精确"
 * （引用里少一个定位层级），而 chunk 内容本身仍完整 —— 不影响答案正确性，
 * 只影响出处的可读性。这是刻意的取舍：不为追求 perfect 而引入复杂解析。
 */
public final class PlainTextSectioning {

    private PlainTextSectioning() {}

    /**
     * 中文制度文本的典型编号标记（<b>只匹配行首的编号本身，不含后面的标题名或正文</b>）：
     * <pre>
     * 第一章 / 第1章 / 第 12 条 / 第二十六条 / 第三项
     * 一、  (一)  1.  1.1  （常见的层级编号）
     * </pre>
     * <b>为什么要这么精细</b>：制度里"第二十五条 党支部应当…"是**编号 + 正文**同一行。
     * 若把整行当标题，路径会变成「第三章 发展对象的确定 第二十五条」——
     * 引用里就会显示一串标题名拼接的怪东西，反而比不给路径更难核对。
     * 所以只取编号做 path，编号之后的部分归入该段正文。
     */
    private static final Pattern HEADING_MARKER = Pattern.compile(
            "^\\s*(第[零一二三四五六七八九十百千0-9]{1,6}[章节条款项]"
                    + "|[（(]?[一二三四五六七八九十]{1,3}[）)、.]"
                    + "|[（(]?\\d{1,2}[）)、.]"
                    + "|\\d{1,2}\\.\\d{1,2})");

    /** 单行超过这个长度就不像标题了（标题通常短）。超长的"第一条…"其实是正文 */
    private static final int MAX_HEADING_LEN = 60;

    /**
     * 把正文切成带路径的段。
     *
     * <p>行为：以"编号行"为界分段。编号本身作为该段的 {@code heading}，
     * 同一行编号之后的内容并入该段正文；章级编号（第 X 章）只用于拼路径，
     * 它后面的标题名不并入正文（那是章节名，不是条款内容）。
     *
     * <p>没有任何编号行时返回**单个段**（整篇正文），路径留空 ——
     * 此时引用只能给到文档级，这是诚实的结果，不伪造一个路径。
     */
    public static List<KnowledgeSource.Section> split(String body) {
        if (body == null || body.isBlank()) return List.of();

        String[] lines = body.replace("\r\n", "\n").replace('\r', '\n').split("\n");

        List<KnowledgeSource.Section> out = new ArrayList<>();
        StringBuilder buf = new StringBuilder();
        String heading = null;
        // 上层标题（"第三章"）保留下来，拼成"第三章 第二十六条"这样的路径
        String chapter = null;

        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty()) {
                if (buf.length() > 0) buf.append('\n');
                continue;
            }

            String marker = markerOf(line);
            if (marker != null) {
                flush(out, path(chapter, heading), buf);
                String rest = line.substring(marker.length()).strip();

                if (isChapter(marker)) {
                    chapter = marker;
                    heading = null;          // 章标题本身不单独成段（正文在条里）
                    continue;                // 章名不入正文
                }
                heading = marker;
                if (!rest.isEmpty()) buf.append(rest);   // 编号后的正文并入本段
                else buf.append('\n');
                continue;
            }

            if (buf.length() > 0 && buf.charAt(buf.length() - 1) != '\n') buf.append('\n');
            buf.append(line);
        }
        flush(out, path(chapter, heading), buf);

        // 一个段都没有（正文全是编号行，或只有一行）→ 兜底整篇
        if (out.isEmpty()) out.add(KnowledgeSource.Section.of(null, body.strip()));
        return out;
    }

    private static void flush(List<KnowledgeSource.Section> out, String path,
                              StringBuilder buf) {
        String content = buf.toString().strip();
        buf.setLength(0);
        if (content.isEmpty()) return;
        out.add(KnowledgeSource.Section.of(path, content));
    }

    private static String path(String chapter, String heading) {
        if (chapter == null) return heading;
        if (heading == null) return null;   // 只有章、无条 → 路径留空，避免误导
        return chapter + " " + heading;
    }

    /**
     * 行首编号标记；不像标题（太长）时返回 {@code null}。
     *
     * <p>长度限制是必要的：制度正文里常有"第一条所述情形…"这类**引用式表述**开头，
     * 若不加限制会被误判成一个新条款，把正文切碎。
     */
    private static String markerOf(String line) {
        if (line.length() > MAX_HEADING_LEN) return null;
        java.util.regex.Matcher m = HEADING_MARKER.matcher(line);
        return m.find() ? m.group(1) : null;
    }

    /** 是否"章"级标记（比条高一层，用于拼路径，且其后的标题名不入正文） */
    private static boolean isChapter(String marker) {
        return marker.endsWith("章");
    }
}
