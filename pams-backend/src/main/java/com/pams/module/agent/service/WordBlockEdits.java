package com.pams.module.agent.service;

import com.pams.common.BizException;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTP;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;
import org.w3c.dom.Node;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Applies exact replacements and bounded structural edits to addressed Word blocks. */
final class WordBlockEdits {
    record BlockRef(String blockId, String expectedHash) {}

    record Edit(String blockId, String expectedHash, String before, String after, String operation,
                List<BlockRef> followingBlocks) {
        Edit(String blockId, String expectedHash, String before, String after) {
            this(blockId, expectedHash, before, after, null, null);
        }

        Operation kind() {
            try {
                return operation == null ? Operation.REPLACE : Operation.valueOf(operation);
            } catch (IllegalArgumentException failure) {
                throw new BizException(409, "不支持此 Word 修订操作");
            }
        }
    }

    enum Operation { REPLACE, INSERT_BEFORE, INSERT_AFTER, DELETE }

    private record TextRun(CTR node, String text) {}
    private record Block(XWPFParagraph paragraph, int bodyIndex, boolean cell, int start, String chunk,
                         String fullText, List<TextRun> runs) {}
    private record Resolved(Edit edit, List<Block> blocks, int start, int end) {}

    private static final int MAX_BLOCKS = 12;
    private static final Pattern ADDRESS = Pattern.compile("b([0-9]{1,5})(?:r([0-9]{1,5})c([0-9]{1,5}))?p([0-9]{1,5})");
    private static final String DOCUMENT_XML = "word/document.xml";
    private static final String WORD_NS = "http://schemas.openxmlformats.org/wordprocessingml/2006/main";

    private WordBlockEdits() {}

    static byte[] apply(PlanSnapshotStore.Snapshot snapshot, List<Edit> edits) throws IOException {
        if (edits == null || edits.isEmpty() || edits.size() > MAX_BLOCKS) {
            throw new BizException(409, "一次可修订 1 至 12 个已读取的文档块");
        }
        var covered = new HashMap<String, Map<String, Object>>();
        for (var block : snapshot.blocks().subList(0, snapshot.covered())) {
            covered.put(block.get("blockId").toString(), block);
        }
        // Bound the original archive before passing it to the Word reader.
        var original = readParts(snapshot.bytes());
        try (var document = new XWPFDocument(new ByteArrayInputStream(snapshot.bytes()));
             var updated = new ByteArrayOutputStream()) {
            // Resolve every address against the original body ordering before mutating it.
            var targets = Collections.newSetFromMap(new IdentityHashMap<XWPFParagraph, Boolean>());
            var resolved = new ArrayList<Resolved>();
            for (Edit edit : edits) {
                var item = resolve(document, covered, edit);
                for (Block block : item.blocks()) {
                    if (!targets.add(block.paragraph()) || targets.size() > MAX_BLOCKS) {
                        throw new BizException(409, "修订目标重叠或超过 12 个段落，请分批处理");
                    }
                }
                resolved.add(item);
            }
            for (var item : resolved) apply(item);
            document.write(updated);
            original.put(DOCUMENT_XML, readParts(updated.toByteArray()).get(DOCUMENT_XML));
        }
        try (var result = new ByteArrayOutputStream()) {
            try (var zip = new ZipOutputStream(result)) {
                for (var part : original.entrySet()) {
                    zip.putNextEntry(new ZipEntry(part.getKey()));
                    zip.write(part.getValue());
                    zip.closeEntry();
                }
            }
            return result.toByteArray();
        }
    }

    private static Resolved resolve(XWPFDocument document, Map<String, Map<String, Object>> covered, Edit edit) {
        if (edit == null || edit.before() == null || edit.after() == null || edit.before().length() > 7200
                || edit.after().length() > 1200 || edit.after().split("\\n", -1).length > MAX_BLOCKS
                || edit.before().codePoints().anyMatch(WordBlockEdits::invalidText)
                || edit.after().codePoints().anyMatch(WordBlockEdits::invalidText)) {
            throw new BizException(409, "修订文本无效或过长");
        }
        var kind = edit.kind();
        var first = block(document, covered, new BlockRef(edit.blockId(), edit.expectedHash()));
        var following = edit.followingBlocks() == null ? List.<BlockRef>of() : edit.followingBlocks();
        if (following.size() >= MAX_BLOCKS || (kind != Operation.REPLACE && !following.isEmpty())) {
            throw new BizException(409, "只有文本替换可引用连续的后续段落");
        }
        var blocks = new ArrayList<Block>();
        blocks.add(first);
        for (var ref : following) {
            var next = block(document, covered, ref);
            if (next.bodyIndex() != first.bodyIndex() + blocks.size()) {
                throw new BizException(409, "跨段落替换须提供顺序连续的正文段落");
            }
            blocks.add(next);
        }
        boolean structural = kind != Operation.REPLACE || blocks.size() > 1 || edit.after().contains("\n");
        if (structural) {
            for (Block target : blocks) requireStructuralParagraph(target);
        }
        if (kind != Operation.REPLACE) {
            if (!first.fullText().equals(edit.before()) ||
                    (kind == Operation.DELETE ? !edit.after().isEmpty() : edit.after().isBlank())) {
                throw new BizException(409, "插入或删除须传入锚点完整原文，删除后的内容须为空");
            }
            return new Resolved(edit, blocks, 0, first.fullText().length());
        }
        if (edit.before().isBlank() || edit.before().equals(edit.after())) {
            throw new BizException(409, "替换须包含非空原文及实际变更");
        }
        if (blocks.size() == 1) {
            int start = uniqueOffset(first.chunk(), edit.before()) + first.start();
            return new Resolved(edit, blocks, start, start + edit.before().length());
        }
        String all = String.join("\n", blocks.stream().map(Block::fullText).toList());
        int start = uniqueOffset(all, edit.before());
        int end = start + edit.before().length();
        int lastStart = all.length() - blocks.getLast().fullText().length();
        if (start > first.fullText().length() || end < lastStart) {
            throw new BizException(409, "原文必须覆盖列出的全部连续段落，段落之间用换行分隔");
        }
        return new Resolved(edit, blocks, start, end - lastStart);
    }

    private static boolean invalidText(int codePoint) {
        return (Character.isISOControl(codePoint) && codePoint != '\n') || codePoint == 0xFFFE
                || codePoint == 0xFFFF || codePoint >= 0xD800 && codePoint <= 0xDFFF;
    }

    private static int uniqueOffset(String text, String before) {
        int offset = text.indexOf(before);
        if (offset < 0 || text.indexOf(before, offset + 1) >= 0) {
            throw new BizException(409, "原文不匹配或匹配不唯一");
        }
        return offset;
    }

    private static Block block(XWPFDocument document, Map<String, Map<String, Object>> covered, BlockRef ref) {
        var expected = ref == null || ref.blockId() == null ? null : covered.get(ref.blockId());
        if (expected == null || !Objects.equals(expected.get("hash"), ref.expectedHash())) {
            throw new BizException(409, "目标块未读取或指纹已变化");
        }
        var match = ADDRESS.matcher(ref.blockId());
        if (!match.matches()) throw new BizException(409, "目标块不是可修订的 Word 正文");
        int bodyIndex = Integer.parseInt(match.group(1));
        boolean inCell = match.group(2) != null;
        var paragraph = paragraph(document, match, bodyIndex, inCell);
        if (!onlyChildren(paragraph.getCTP().getDomNode(), Set.of("pPr", "r"))) {
            throw new BizException(409, "目标段落包含书签、批注、超链接或其他复杂结构");
        }
        var runs = new ArrayList<TextRun>();
        var fullText = new StringBuilder();
        for (CTR run : paragraph.getCTP().getRList()) {
            if (!onlyChildren(run.getDomNode(), Set.of("rPr", "t"))) {
                throw new BizException(409, "目标段落包含域、图片或其他复杂内容");
            }
            String text = String.join("", run.getTList().stream().map(t -> t.getStringValue()).toList());
            fullText.append(text);
            runs.add(new TextRun(run, text));
        }
        int start = Integer.parseInt(match.group(4)) * PlanDocumentReader.CHUNK_LENGTH;
        String chunk = expected.get("text").toString();
        if (!fullText.toString().equals(paragraph.getText()) || start > fullText.length()
                || !fullText.substring(start, Math.min(start + PlanDocumentReader.CHUNK_LENGTH, fullText.length())).equals(chunk)) {
            throw new BizException(409, "目标块与原文快照不一致");
        }
        return new Block(paragraph, bodyIndex, inCell, start, chunk, fullText.toString(), runs);
    }

    private static XWPFParagraph paragraph(XWPFDocument document, java.util.regex.Matcher match, int index, boolean cell) {
        try {
            var body = document.getBodyElements().get(index);
            if (!cell && body instanceof XWPFParagraph paragraph) return paragraph;
            if (cell && body instanceof XWPFTable table) {
                var target = table.getRows().get(Integer.parseInt(match.group(2))).getTableCells().get(Integer.parseInt(match.group(3)));
                if (target.getBodyElements().size() == 1 && target.getBodyElements().getFirst() instanceof XWPFParagraph paragraph) {
                    return paragraph;
                }
            }
        } catch (IndexOutOfBoundsException ignored) {
            throw new BizException(409, "文档块不存在");
        }
        throw new BizException(409, "目前仅支持正文段落和单段落表格单元格");
    }

    private static void requireStructuralParagraph(Block block) {
        var properties = block.paragraph().getCTP().getPPr();
        if (block.cell() || block.start() != 0 || !block.chunk().equals(block.fullText())
                || properties != null && properties.isSetSectPr()) {
            throw new BizException(409, "结构修改仅支持完整的正文段落，不能跨单元格、长段落切片或分节符");
        }
    }

    private static void apply(Resolved item) {
        var first = item.blocks().getFirst();
        switch (item.edit().kind()) {
            case DELETE -> remove(first.paragraph().getCTP());
            case INSERT_BEFORE, INSERT_AFTER -> {
                CTP anchor = first.paragraph().getCTP();
                boolean after = item.edit().kind() == Operation.INSERT_AFTER;
                for (String line : item.edit().after().split("\n", -1)) {
                    anchor = insert(anchor, first.paragraph().getCTP(), after);
                    anchor.addNewR().set(textWithStyle(first, 0, line));
                    after = true;
                }
            }
            case REPLACE -> replace(item);
        }
    }

    private static void replace(Resolved item) {
        var blocks = item.blocks();
        var first = blocks.getFirst();
        var last = blocks.getLast();
        String[] lines = item.edit().after().split("\n", -1);
        var contents = new ArrayList<List<CTR>>();
        for (int index = 0; index < lines.length; index++) {
            var parts = new ArrayList<CTR>();
            if (index == 0) parts.addAll(slice(first, 0, item.start()));
            if (!lines[index].isEmpty()) {
                Block template = index == lines.length - 1 && index != 0
                        ? last : blocks.get(Math.min(index, blocks.size() - 1));
                parts.add(textWithStyle(template, index == 0 ? item.start() : 0, lines[index]));
            }
            if (index == lines.length - 1) parts.addAll(slice(last, item.end(), last.fullText().length()));
            contents.add(parts);
        }
        // Preserve existing paragraph properties where possible. When a range changes paragraph count,
        // new paragraphs inherit the first paragraph's properties and a retained last paragraph keeps its own.
        var existing = new ArrayList<CTP>();
        existing.add(first.paragraph().getCTP());
        for (int index = 1; index < lines.length - 1 && index < blocks.size() - 1; index++) {
            existing.add(blocks.get(index).paragraph().getCTP());
        }
        var anchor = existing.getLast();
        int reservedLast = lines.length > 1 && blocks.size() > 1 ? 1 : 0;
        while (existing.size() < lines.length - reservedLast) {
            anchor = insert(anchor, first.paragraph().getCTP(), true);
            existing.add(anchor);
        }
        if (reservedLast == 1) existing.add(last.paragraph().getCTP());
        for (Block block : blocks) {
            CTP node = block.paragraph().getCTP();
            if (existing.stream().noneMatch(kept -> kept == node)) remove(node);
        }
        for (int index = 0; index < contents.size(); index++) {
            var paragraph = existing.get(index);
            paragraph.setRArray(contents.get(index).toArray(CTR[]::new));
        }
    }

    private static List<CTR> slice(Block block, int from, int to) {
        var result = new ArrayList<CTR>();
        int position = 0;
        for (var run : block.runs()) {
            int length = run.text().length();
            int start = Math.max(0, from - position);
            int end = Math.min(length, to - position);
            if (start < end) {
                var copy = (CTR) run.node().copy();
                if (start != 0 || end != length) setText(copy, run.text().substring(start, end));
                result.add(copy);
            }
            position += length;
        }
        return result;
    }

    private static CTR textWithStyle(Block block, int offset, String text) {
        var result = CTR.Factory.newInstance();
        TextRun style = block.runs().isEmpty() ? null : block.runs().getLast();
        for (var run : block.runs()) {
            if (offset < run.text().length()) {
                style = run;
                break;
            }
            offset -= run.text().length();
        }
        if (style != null && style.node().isSetRPr()) result.setRPr(style.node().getRPr());
        setText(result, text);
        return result;
    }

    private static void setText(CTR node, String text) {
        while (node.sizeOfTArray() > 0) node.removeT(0);
        var value = node.addNewT();
        value.setStringValue(text);
        if (!text.isEmpty() && (Character.isWhitespace(text.charAt(0)) || Character.isWhitespace(text.charAt(text.length() - 1)))) {
            try (var cursor = value.newCursor()) {
                cursor.toNextToken();
                cursor.insertAttributeWithValue(new QName(XMLConstants.XML_NS_URI, "space"), "preserve");
            }
        }
    }

    private static CTP insert(CTP anchor, CTP template, boolean after) {
        try (var cursor = anchor.newCursor()) {
            if (after) {
                cursor.toEndToken();
                cursor.toNextToken();
            }
            cursor.beginElement("p", WORD_NS);
            cursor.toParent();
            var paragraph = (CTP) cursor.getObject();
            if (template.isSetPPr()) paragraph.setPPr(template.getPPr());
            return paragraph;
        }
    }

    private static void remove(CTP paragraph) {
        try (var cursor = paragraph.newCursor()) {
            cursor.removeXml();
        }
    }

    private static boolean onlyChildren(Node parent, Set<String> allowed) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getNodeType() == Node.ELEMENT_NODE
                    && (!WORD_NS.equals(child.getNamespaceURI()) || !allowed.contains(child.getLocalName()))) return false;
        }
        return true;
    }

    static Map<String, byte[]> readParts(byte[] bytes) throws IOException {
        var parts = new LinkedHashMap<String, byte[]>();
        int remaining = 24 * 1024 * 1024;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for (ZipEntry part; (part = zip.getNextEntry()) != null;) {
                byte[] contents = zip.readNBytes(remaining + 1);
                remaining -= contents.length;
                if (remaining < 0 || parts.size() >= 512 || parts.putIfAbsent(part.getName(), contents) != null) {
                    throw new IOException("DOCX 包超过处理限制或含重复部件");
                }
            }
        }
        if (!parts.containsKey(DOCUMENT_XML)) throw new IOException("缺少 Word 正文");
        return parts;
    }
}
