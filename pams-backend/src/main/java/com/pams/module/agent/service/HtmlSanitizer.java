package com.pams.module.agent.service;

import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;
import org.springframework.stereotype.Component;

/**
 * 服务端 HTML 净化（业务设计方案 D10，B0.5）——<b>唯一真正的硬前置</b>。
 *
 * <p>为什么必须做：Agent 会把 LLM 输出<b>写入数据库</b>（策划书章节 / 推文正文），
 * 而写库内容不再经过任何前端防线。前端 DOMPurify 只保护"自己的渲染路径"——
 * API 可被直接调用，入库的脏 HTML 会在<b>所有</b>读取路径上生效。
 *
 * <p>为什么不能自研正则净化：HTML 解析不能用正则做（明确的反模式）。
 *
 * <p><b>落地位置</b>：Service 层入口（不是 Controller）——因为 Agent 走 Service 直调，
 * 挂 Controller 会被绕过（这正是 D3 的同一个坑）。
 *
 * <p><b>⚠️ 一个容易漏的点</b>：净化必须放在 <b>Agent 写路径与人工写路径的公共入口</b>。
 * 如果只在 Agent 的写入工具里调净化，人工通过编辑器提交的脏 HTML 仍然入库——
 * 那是<b>既有漏洞</b>，Agent 只是让它更明显。本类提供静态与实例两种入口，
 * 便于业务 Service 直接调用（顺带覆盖人工路径，零额外成本）。
 */
@Slf4j
@Component
public class HtmlSanitizer {

    /**
     * 白名单与 <b>wangEditor5 产出的标签集对齐</b>（D10）。
     * 协议白名单 http/https/mailto，禁止 javascript: / data:。
     */
    private static final Safelist WANG_SAFELIST = buildSafelist();

    private static Safelist buildSafelist() {
        Safelist s = new Safelist()
                .addTags("p", "br", "strong", "b", "em", "i", "u", "s", "h1", "h2", "h3", "h4", "h5",
                        "ul", "ol", "li", "blockquote", "pre", "code", "span", "div",
                        "table", "thead", "tbody", "tr", "td", "th", "a", "img", "hr")
                .addAttributes("a", "href", "title", "target", "rel")
                .addAttributes("img", "src", "alt", "title", "width", "height")
                .addAttributes("td", "colspan", "rowspan", "style")
                .addAttributes("th", "colspan", "rowspan", "style")
                .addAttributes("table", "style", "border")
                .addAttributes("p", "style")
                .addAttributes("div", "style")
                .addAttributes("span", "style")
                .addAttributes("h1", "style").addAttributes("h2", "style").addAttributes("h3", "style")
                .addAttributes("h4", "style").addAttributes("h5", "style")
                .addAttributes("blockquote", "style")
                .addAttributes("li", "style")
                .addProtocols("a", "href", "http", "https", "mailto")
                .addProtocols("img", "src", "http", "https");
        return s;
    }

    /**
     * style 需再限定到少量安全属性（D10：如 text-align / text-indent）。
     * Jsoup 的 Safelist 不管 style 内部，故这里做二次收敛——
     * 这是"白名单口径"里最容易只做一半的地方。
     */
    private static final java.util.Set<String> SAFE_STYLE_PROPS = java.util.Set.of(
            "text-align", "text-indent", "font-weight", "font-style",
            "color", "background-color", "width", "height", "margin", "padding"
    );

    /** 危险样式模式（expression() 是 IE 时代的 XSS 载体，验收用例之一） */
    private static final java.util.regex.Pattern DANGEROUS_STYLE =
            java.util.regex.Pattern.compile("(?i)(expression\\s*\\(|javascript:|url\\s*\\(\\s*['\"]?\\s*javascript|behavi" + "or\\s*:)");

    /**
     * 净化富文本 HTML。
     *
     * @param html 原始 HTML（可为 null）
     * @return 净化后的 HTML；输入为 null/空时返回空串
     */
    public String sanitize(String html) {
        return sanitize(html, false);
    }

    /**
     * @param forceBodyOnly true = 只保留 body 内部片段（用于编辑器局部内容，如策划书某章节）
     */
    public String sanitize(String html, boolean forceBodyOnly) {
        if (html == null || html.isBlank()) return "";
        try {
            Document.OutputSettings out = new Document.OutputSettings().prettyPrint(false);
            String clean = Jsoup.clean(html, "", WANG_SAFELIST, out);
            clean = scrubStyles(clean);
            if (forceBodyOnly) {
                Document doc = Jsoup.parseBodyFragment(clean);
                clean = doc.body().html();
            }
            return clean;
        } catch (Exception e) {
            log.warn("HTML 净化失败，降级为纯文本（宁少内容，不放脏数据）", e);
            return Jsoup.parse(html).text();
        }
    }

    /**
     * 二次收敛 style：只保留安全属性。
     * 用 HTML 解析器（不是正则）遍历元素后逐条过滤——这是"用对工具"的关键。
     */
    private String scrubStyles(String html) {
        if (html == null || html.isEmpty()) return html;
        Document doc = Jsoup.parseBodyFragment(html);
        doc.outputSettings().prettyPrint(false);
        for (Element el : doc.getAllElements()) {
            String style = el.attr("style");
            if (style == null || style.isBlank()) continue;
            if (DANGEROUS_STYLE.matcher(style).find()) {
                el.removeAttr("style");
                continue;
            }
            StringBuilder kept = new StringBuilder();
            for (String decl : style.split(";")) {
                int idx = decl.indexOf(':');
                if (idx <= 0) continue;
                String prop = decl.substring(0, idx).trim().toLowerCase();
                String val = decl.substring(idx + 1).trim();
                if (!SAFE_STYLE_PROPS.contains(prop)) continue;
                if (DANGEROUS_STYLE.matcher(val).find()) continue;
                if (val.contains("<") || val.contains(">")) continue;
                if (kept.length() > 0) kept.append(';');
                kept.append(prop).append(':').append(val);
            }
            if (kept.length() == 0) el.removeAttr("style");
            else el.attr("style", kept.toString());
        }
        return doc.body().html();
    }

    /** 纯文本转义（用于生成类工具落地纯文本字段，如 Article.summary） */
    public String toPlainText(String html) {
        if (html == null || html.isBlank()) return "";
        return Jsoup.parse(html).text();
    }

    /** 静态入口：业务 Service 可直接调用，便于"顺带覆盖人工写路径"（D10） */
    public static String clean(String html) {
        return new HtmlSanitizer().sanitize(html);
    }
}
