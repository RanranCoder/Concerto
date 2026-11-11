package com.pams.module.agent.service;

import com.pams.module.activity.dto.PlanRequest;
import com.pams.module.activity.entity.ActivityPlan;
import com.pams.module.activity.service.PlanService;
import com.pams.module.agent.core.ToolContext;
import com.pams.module.archive.service.FileStorageService;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;
import java.nio.file.Files;

/** Converts supported rich text to native Word blocks, then saves a new plan version. */
@Service
public class NativePlanDraftWriter {
    private final PlanService plans;
    private final FileStorageService storage;
    private final WordPageReview pageReview;

    public NativePlanDraftWriter(PlanService plans, FileStorageService storage, WordPageReview pageReview) {
        this.plans = plans;
        this.storage = storage;
        this.pageReview = pageReview;
    }

    public record SavedDraft(ActivityPlan plan, WordPageReview.Report pageReview) {}

    @Transactional(rollbackFor = Exception.class)
    public SavedDraft save(PlanRequest request, ToolContext ctx, Long baseId, Long baseRevision) throws IOException {
        byte[] bytes = render(request.getContent());
        var report = pageReview.inspect(bytes);
        if (report.issues().stream().anyMatch(issue -> !issue.code().equals("UNREADABLE_TEXT"))) {
            byte[] adjusted = render(request.getContent(), true);
            var checked = pageReview.inspect(adjusted);
            if (checked.pages() > 0 && checked.issues().size() < report.issues().size()) {
                bytes = adjusted;
                report = new WordPageReview.Report(checked.status(), checked.pages(), checked.issues(), true);
            }
        }
        var file = storage.store(new DocumentFile(bytes), "PLAN", ctx.getUserId());
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCompletion(int status) {
                if (status == STATUS_ROLLED_BACK) {
                    try { Files.deleteIfExists(storage.physicalPath(file.getPath())); }
                    catch (IOException ignored) { /* File storage orphan cleanup can retry. */ }
                }
            }
        });
        request.setDocumentFileId(file.getId());
        return new SavedDraft(plans.createAgentVersion(request, ctx, baseId, baseRevision), report);
    }

    static byte[] render(String html) throws IOException {
        return render(html, false);
    }

    private static byte[] render(String html, boolean compact) throws IOException {
        try (var doc = new XWPFDocument(); var bytes = new ByteArrayOutputStream()) {
            var body = Jsoup.parseBodyFragment(html).body();
            if (!body.hasText()) throw new IOException("策划书正文为空");
            for (Node node : body.childNodes()) writeBlock(doc, node);
            if (compact) {
                for (var paragraph : doc.getParagraphs()) {
                    paragraph.setSpacingBefore(0);
                    paragraph.setSpacingAfter(0);
                    paragraph.setKeepNext(false);
                }
                for (var table : doc.getTables()) {
                    for (var row : table.getRows()) for (var cell : row.getTableCells()) {
                        for (var paragraph : cell.getParagraphs()) {
                            paragraph.setSpacingBefore(0);
                            paragraph.setSpacingAfter(0);
                        }
                    }
                }
            }
            doc.write(bytes);
            return bytes.toByteArray();
        }
    }

    private static void writeBlock(XWPFDocument doc, Node node) {
        if (node instanceof TextNode text) {
            if (!text.isBlank()) doc.createParagraph().createRun().setText(text.text().strip());
            return;
        }
        if (!(node instanceof Element element)) return;
        switch (element.normalName()) {
            case "div", "section", "article" -> element.childNodes().forEach(child -> writeBlock(doc, child));
            case "table" -> {
                var rows = element.select("> tr, > thead > tr, > tbody > tr, > tfoot > tr");
                int columns = rows.stream().mapToInt(row -> row.childrenSize()).max().orElse(0);
                if (columns == 0) return;
                var table = doc.createTable(rows.size(), columns);
                table.setWidth("100%");
                for (int row = 0; row < rows.size(); row++) {
                    for (int col = 0; col < rows.get(row).childrenSize(); col++) {
                        var cell = rows.get(row).child(col);
                        writeInline(table.getRow(row).getCell(col).getParagraphs().getFirst(), cell, cell.normalName().equals("th"), false, 11);
                    }
                }
            }
            case "ul", "ol" -> {
                int index = 1;
                for (Element item : element.select("> li")) {
                    var paragraph = doc.createParagraph();
                    paragraph.createRun().setText(element.normalName().equals("ol") ? index++ + ". " : "• ");
                    writeInline(paragraph, item, false, false, 11);
                }
            }
            default -> {
                boolean heading = element.normalName().matches("h[1-6]");
                var paragraph = doc.createParagraph();
                if (heading) {
                    paragraph.setSpacingBefore(200);
                    paragraph.setKeepNext(true);
                }
                writeInline(paragraph, element, heading, false, heading ? (element.normalName().equals("h1") ? 18 : 14) : 11);
            }
        }
    }

    private static void writeInline(XWPFParagraph paragraph, Node node, boolean bold, boolean italic, int size) {
        if (node instanceof TextNode text) {
            var run = paragraph.createRun();
            run.setBold(bold);
            run.setItalic(italic);
            run.setFontSize(size);
            run.setText(text.text());
        } else if (node instanceof Element element) {
            String tag = element.normalName();
            if (tag.equals("br")) paragraph.createRun().addBreak();
            else for (Node child : element.childNodes()) {
                writeInline(paragraph, child, bold || tag.equals("b") || tag.equals("strong"),
                        italic || tag.equals("em") || tag.equals("i"), size);
            }
        }
    }

    private record DocumentFile(byte[] bytes) implements MultipartFile {
        public String getName() { return "document"; }
        public String getOriginalFilename() { return "策划书草稿.docx"; }
        public String getContentType() { return "application/vnd.openxmlformats-officedocument.wordprocessingml.document"; }
        public boolean isEmpty() { return bytes.length == 0; }
        public long getSize() { return bytes.length; }
        public byte[] getBytes() { return bytes.clone(); }
        public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
        public void transferTo(File destination) throws IOException { Files.write(destination.toPath(), bytes); }
    }
}
