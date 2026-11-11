package com.pams.module.agent.service;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;

class WordDraftEditsTest {
    @Test
    void replacesTextAcrossRunsWhilePreservingFormattingOutsideTheRange() throws Exception {
        byte[] original;
        try (var doc = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            var p = doc.createParagraph();
            var prefix = p.createRun();
            prefix.setBold(true);
            prefix.setText("负责人：");
            var start = p.createRun();
            start.setItalic(true);
            start.setText("行政");
            var end = p.createRun();
            end.setColor("003399");
            end.setText("部（确认）");
            doc.createParagraph().createRun().setText("其他段落保持");
            doc.write(output);
            original = output.toByteArray();
        }
        var source = snapshot(original);
        byte[] revised = WordBlockEdits.apply(source, List.of(edit(source.blocks(), "b0p0", "政部", "务组")));
        try (var doc = new XWPFDocument(new ByteArrayInputStream(revised))) {
            var runs = doc.getParagraphs().getFirst().getRuns();
            assertThat(doc.getParagraphs().getFirst().getText()).isEqualTo("负责人：行务组（确认）");
            assertThat(runs.getFirst().isBold()).isTrue();
            assertThat(runs.stream().filter(run -> "务组".equals(run.getText(0))).findFirst().orElseThrow().isItalic()).isTrue();
            assertThat(runs.getLast().getColor()).isEqualTo("003399");
        }
        var revisedParts = WordBlockEdits.readParts(revised);
        for (var part : WordBlockEdits.readParts(original).entrySet()) {
            if (!part.getKey().equals("word/document.xml")) {
                assertThat(revisedParts.get(part.getKey())).as(part.getKey()).isEqualTo(part.getValue());
            }
        }
    }

    @Test
    void structuralOperationsKeepOriginalAnchorsAndTheTextOutsideAContiguousRange() throws Exception {
        var source = snapshot(NativePlanDraftWriter.render(
                "<p>前缀原</p><p>中段</p><p>结尾后缀</p><p>保留</p><p>删除</p><p>原最后段</p>"));
        var blocks = source.blocks();
        var range = new WordBlockEdits.Edit("b0p0", hash(blocks, "b0p0"), "原\n中段\n结尾", "新\n方案", "REPLACE",
                List.of(new WordBlockEdits.BlockRef("b1p0", hash(blocks, "b1p0")),
                        new WordBlockEdits.BlockRef("b2p0", hash(blocks, "b2p0"))));
        var insert = new WordBlockEdits.Edit("b3p0", hash(blocks, "b3p0"), "保留", "新增一\n新增二", "INSERT_AFTER", null);
        var delete = new WordBlockEdits.Edit("b4p0", hash(blocks, "b4p0"), "删除", "", "DELETE", null);
        var revised = WordBlockEdits.apply(source, List.of(range, insert, delete, edit(blocks, "b5p0", "原", "新")));
        assertThat(PlanDocumentReader.read(revised).blocks()).extracting(block -> block.get("text"))
                .containsExactly("前缀新", "方案后缀", "保留", "新增一", "新增二", "新最后段");
        assertThatThrownBy(() -> WordBlockEdits.apply(source, List.of(range, edit(blocks, "b2p0", "结尾", "修改"))))
                .hasMessageContaining("重叠");
        var gap = new WordBlockEdits.Edit("b0p0", hash(blocks, "b0p0"), "原\n结尾", "新", "REPLACE",
                List.of(new WordBlockEdits.BlockRef("b2p0", hash(blocks, "b2p0"))));
        assertThatThrownBy(() -> WordBlockEdits.apply(source, List.of(gap))).hasMessageContaining("连续");
    }

    @Test
    void aLaterSliceCanBeEditedWithinItsOwnFingerprint() throws Exception {
        String prefix = "原".repeat(600);
        var source = snapshot(NativePlanDraftWriter.render("<p>" + prefix + "后续</p>"));
        var revised = WordBlockEdits.apply(source, List.of(edit(source.blocks(), "b0p1", "后续", "更新")));
        assertThat(PlanDocumentReader.read(revised).blocks()).extracting(block -> block.get("text"))
                .containsExactly(prefix, "更新");
    }
    @Test
    void generatedWordCanBeEditedInPlaceAcrossParagraphAndTableWhileKeepingFormatting() throws Exception {
        byte[] original = NativePlanDraftWriter.render("""
                <h1>培训班策划</h1><p><strong>负责部门</strong> 办公室</p>
                <table><tr><th>项目</th><th>预算</th></tr><tr><td>物料</td><td>2000 元</td></tr></table>
                """);
        var blocks = PlanDocumentReader.read(original).blocks();
        var snapshot = new PlanSnapshotStore.Snapshot("snapshot", 1L, "baseline", original, blocks,
                blocks.size(), blocks.size(), false, LocalDateTime.now().plusHours(1));
        var edits = List.of(edit(blocks, "b1p0", "办公室", "文秘部"),
                edit(blocks, "b2r1c1p0", "2000", "2500"));
        byte[] revised = WordBlockEdits.apply(snapshot, edits);
        try (var doc = new XWPFDocument(new ByteArrayInputStream(revised))) {
            assertThat(doc.getParagraphs().getFirst().getText()).isEqualTo("培训班策划");
            var paragraph = doc.getParagraphs().get(1);
            assertThat(paragraph.getText()).isEqualTo("负责部门 文秘部");
            assertThat(paragraph.getRuns().getFirst().isBold()).isTrue();
            assertThat(doc.getTables().getFirst().getRow(1).getCell(1).getText()).isEqualTo("2500 元");
        }
        assertThat(PlanDocumentReader.read(original).blocks()).isEqualTo(blocks);
        assertThatThrownBy(() -> WordBlockEdits.apply(snapshot, List.of(new WordBlockEdits.Edit("b1p0", "stale", "办公室", "文秘部"))))
                .hasMessageContaining("指纹");
        var partial = new PlanSnapshotStore.Snapshot("snapshot", 1L, "baseline", original, blocks, 2, blocks.size(), false, snapshot.expires());
        assertThatThrownBy(() -> WordBlockEdits.apply(partial, List.of(edits.getLast()))).hasMessageContaining("未读取");
    }

    private WordBlockEdits.Edit edit(List<Map<String,Object>> blocks, String id, String before, String after) {
        return new WordBlockEdits.Edit(id, hash(blocks, id), before, after);
    }

    private String hash(List<Map<String,Object>> blocks, String id) {
        return blocks.stream().filter(item -> id.equals(item.get("blockId"))).findFirst().orElseThrow().get("hash").toString();
    }

    private PlanSnapshotStore.Snapshot snapshot(byte[] original) throws Exception {
        var blocks = PlanDocumentReader.read(original).blocks();
        return new PlanSnapshotStore.Snapshot("snapshot", 1L, "baseline", original, blocks,
                blocks.size(), blocks.size(), false, LocalDateTime.now().plusHours(1));
    }
}
