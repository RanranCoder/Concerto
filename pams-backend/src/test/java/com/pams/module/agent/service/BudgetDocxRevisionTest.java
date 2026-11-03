package com.pams.module.agent.service;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import static org.assertj.core.api.Assertions.*;

class BudgetDocxRevisionTest {
    byte[] doc(String... paragraphs) throws Exception {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            for(var text:paragraphs) {var run=doc.createParagraph().createRun();run.setBold(true);run.setColor("112233");run.setText(text);}
            doc.write(out);return out.toByteArray();
        }
    }
    Map<String,byte[]> parts(byte[] bytes)throws Exception {
        var map=new HashMap<String,byte[]>();try(var zip=new ZipInputStream(new ByteArrayInputStream(bytes))) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) map.put(entry.getName(),zip.readAllBytes());
        }return map;
    }
    @Test void onlyBodyXmlChangesAndAllOtherPackagePartsAreByteIdentical() throws Exception {
        byte[] original=doc("原标题","预算总额为2500元","原流程保留");
        byte[] revised=BudgetDocxRevision.replace(original,"预算总额为2500元","预算总额为3000元");
        var before=parts(original);var after=parts(revised);assertThat(after.keySet()).isEqualTo(before.keySet());
        for(String key:before.keySet()) if(!key.equals("word/document.xml")) assertThat(after.get(key)).as(key).isEqualTo(before.get(key));
        try(var doc=new XWPFDocument(new ByteArrayInputStream(revised))) {
            assertThat(doc.getParagraphs()).extracting(XWPFParagraph::getText).containsExactly("原标题","预算总额为3000元","原流程保留");
            assertThat(doc.getParagraphs().get(1).getRuns().getFirst().isBold()).isTrue();
            assertThat(doc.getParagraphs().get(1).getRuns().getFirst().getColor()).isEqualTo("112233");
        }
    }
    @Test void complexElementsOrDuplicateTargetAreRejected() throws Exception {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()) {
            doc.createTable(1,1).getRow(0).getCell(0).setText("预算总额为2500元");doc.write(out);
            assertThatThrownBy(()->BudgetDocxRevision.validate(out.toByteArray())).hasMessageContaining("Unsupported");
        }
        var duplicate=doc("预算总额为2500元","预算总额为2500元");
        assertThatThrownBy(()->BudgetDocxRevision.replace(duplicate,"预算总额为2500元","预算总额为3000元")).hasMessageContaining("Ambiguous");
    }
    @Test void amountConstraintCannotAuthorizeOtherEditsOrConflictingItemTotals() {
        var snapshot=new PlanSnapshotStore.Snapshot("id",1L,"base",new byte[0],List.of(Map.of("text","预算总额为2500元")),1,1,true,java.time.LocalDateTime.now().plusHours(1));
        assertThatCode(()->PlanRevisionService.validateChange(snapshot,"预算总额为2500元","预算总额为3000元","3000")).doesNotThrowAnyException();
        assertThatThrownBy(()->PlanRevisionService.validateChange(snapshot,"预算总额为2500元","预算总额为9000元","3000")).hasMessageContaining("超出");
        var conflict=new PlanSnapshotStore.Snapshot("id",1L,"base",new byte[0],List.of(Map.of("text","预算总额为2500元"),Map.of("text","物料小计2500元")),2,2,true,java.time.LocalDateTime.now().plusHours(1));
        assertThatThrownBy(()->PlanRevisionService.validateChange(conflict,"预算总额为2500元","预算总额为3000元","3000")).hasMessageContaining("CONFLICT");
    }
}
