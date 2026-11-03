package com.pams.module.agent.service;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import static org.assertj.core.api.Assertions.*;

class PlanDocumentReaderTest {
    @Test void nativeParagraphsAndCellsRetainTextAndStableSnapshotAddressesWithoutRewritingBytes() throws Exception {
        byte[] bytes;
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            var p=doc.createParagraph();var run=p.createRun();run.setBold(true);run.setText("预算总额为2500元");
            var table=doc.createTable(1,2);table.getRow(0).getCell(0).setText("物料");table.getRow(0).getCell(1).setText("2500元");
            doc.write(out);bytes=out.toByteArray();
        }
        var original=bytes.clone();var first=PlanDocumentReader.read(bytes);var second=PlanDocumentReader.read(bytes);
        assertThat(bytes).isEqualTo(original);
        assertThat(first).isEqualTo(second);
        assertThat(first.unsupportedElements()).isEmpty();
        assertThat(first.blocks()).extracting(b->b.get("text")).contains("预算总额为2500元","物料","2500元");
        assertThat(first.blocks()).extracting(b->b.get("blockId")).contains("b0p0","b1r0c1p0");
        try(var doc=new XWPFDocument(new ByteArrayInputStream(bytes))) {assertThat(doc.getParagraphs().getFirst().getRuns().getFirst().isBold()).isTrue();}
    }
    @Test void headersAndTrackedContentAreExplicitlyUnsupportedAndLongParagraphsAreSplit() throws Exception {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.createParagraph().createRun().setText("长".repeat(1400));
            doc.createHeader(org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT).createParagraph().createRun().setText("页眉");
            doc.getDocument().getBody().addNewP().addNewIns();
            doc.write(out);var result=PlanDocumentReader.read(out.toByteArray());
            assertThat(result.unsupportedElements()).contains("HEADER_FOOTER","ins");
            assertThat(result.blocks().stream().map(b->b.get("text").toString()).reduce("",String::concat)).isEqualTo("长".repeat(1400));
            assertThat(result.blocks()).allSatisfy(b->assertThat(b.get("text").toString().length()).isLessThanOrEqualTo(600));
        }
    }
    @Test void encryptedOrInvalidInputDoesNotBecomeAnEmptySuccessfulDocument() {
        assertThatThrownBy(()->PlanDocumentReader.read(new byte[]{1,2,3})).isInstanceOf(Exception.class);
    }
}
